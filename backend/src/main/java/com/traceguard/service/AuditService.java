package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.entity.AuditLog;
import com.traceguard.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作审计日志服务
 */
@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AuditLogMapper auditLogMapper;

    /**
     * 我的操作足迹（个性化增强 BATCH-5）：当前用户最近操作，按时间倒序
     */
    public java.util.List<AuditLog> listMine(Long userId, int limit) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

    /**
     * 异步记录操作日志（不阻塞主请求）
     */
    @Async
    public void record(Long userId, String username, String operation, String method,
                       String path, String params, String ip,
                       Integer statusCode, Long costMs, String errorMsg) {
        try {
            AuditLog log = new AuditLog();
            log.setUserId(userId);
            log.setUsername(username);
            log.setOperation(operation);
            log.setMethod(method);
            log.setPath(path != null && path.length() > 500 ? path.substring(0, 500) : path);
            log.setParams(params != null && params.length() > 1000 ? params.substring(0, 1000) : params);
            log.setIp(ip);
            log.setStatusCode(statusCode);
            log.setCostMs(costMs);
            log.setSuccess(errorMsg == null ? 1 : 0);
            log.setErrorMsg(errorMsg != null && errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg);
            log.setCreateTime(LocalDateTime.now());
            // AUD-08：哈希链——以最近一条日志的 cur_hash 作为本记录 prev_hash，再计算本记录 cur_hash
            String prevHash = latestCurHash();
            log.setPrevHash(prevHash);
            log.setCurHash(com.traceguard.util.HashChainUtil.computeCurHash(log, prevHash));
            auditLogMapper.insert(log);
        } catch (Exception e) {
            LOGGER.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 获取最近一条审计记录的 cur_hash，作为新记录的 prev_hash（AUD-08）。
     * 表为空或异常时返回链头前缀。
     */
    private String latestCurHash() {
        try {
            LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(AuditLog::getCurHash)
                   .orderByDesc(AuditLog::getCreateTime)
                   .last("LIMIT 1");
            AuditLog last = auditLogMapper.selectOne(wrapper);
            if (last != null && last.getCurHash() != null && !last.getCurHash().isEmpty()) {
                return last.getCurHash();
            }
        } catch (Exception e) {
            LOGGER.warn("审计日志哈希链查询失败: {}", e.getMessage());
        }
        return com.traceguard.util.HashChainUtil.GENESIS;
    }

    /**
     * 校验审计日志哈希链完整性（AUD-08）。
     * 按时间升序逐条重算 SHA-256 并与存储值比对，返回首条异常记录及总数（无异常返回 null）。
     */
    public Map<String, Object> verifyChain() {
        Map<String, Object> result = new HashMap<>();
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AuditLog::getCreateTime);
        java.util.List<AuditLog> all = auditLogMapper.selectList(wrapper);
        String prev = com.traceguard.util.HashChainUtil.GENESIS;
        int index = 0;
        int firstBroken = -1;
        for (AuditLog log : all) {
            if (!com.traceguard.util.HashChainUtil.verify(log, prev)) {
                if (firstBroken < 0) {
                    firstBroken = index;
                }
            } else {
                prev = log.getCurHash();
            }
            index++;
        }
        result.put("total", all.size());
        result.put("valid", firstBroken < 0);
        result.put("firstBrokenIndex", firstBroken);
        if (firstBroken >= 0 && firstBroken < all.size()) {
            AuditLog broken = all.get(firstBroken);
            result.put("firstBrokenId", broken.getId());
            result.put("firstBrokenTime", broken.getCreateTime());
        }
        return result;
    }

    /**
     * 分页查询审计日志（仅管理员）
     */
    public Map<String, Object> page(int pageNum, int pageSize, String keyword) {
        Page<AuditLog> page = new Page<>(pageNum, pageSize);
        Page<AuditLog> result = auditLogMapper.selectPage(page, buildQueryWrapper(keyword));
        Map<String, Object> data = new HashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return data;
    }

    private LambdaQueryWrapper<AuditLog> buildQueryWrapper(String keyword) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(AuditLog::getUsername, keyword.trim())
                   .or().like(AuditLog::getOperation, keyword.trim())
                   .or().like(AuditLog::getPath, keyword.trim());
        }
        wrapper.orderByDesc(AuditLog::getCreateTime);
        return wrapper;
    }

    /**
     * 导出审计日志Excel（FR-PLAT-004 操作日志查询与导出），单次最多导出10000条
     */
    public byte[] exportExcel(String keyword) throws Exception {
        Page<AuditLog> page = new Page<>(1, 10000);
        Page<AuditLog> result = auditLogMapper.selectPage(page, buildQueryWrapper(keyword));
        java.util.List<AuditLog> logs = result.getRecords();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("审计日志");
            String[] headers = {"时间", "用户", "操作", "方法", "路径", "参数", "状态码", "结果", "耗时(ms)", "IP", "错误信息"};
            org.apache.poi.xssf.usermodel.XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowIdx = 1;
            for (AuditLog log : logs) {
                org.apache.poi.xssf.usermodel.XSSFRow row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(log.getCreateTime() != null ? dtf.format(log.getCreateTime()) : "");
                row.createCell(1).setCellValue(log.getUsername() != null ? log.getUsername() : "");
                row.createCell(2).setCellValue(log.getOperation() != null ? log.getOperation() : "");
                row.createCell(3).setCellValue(log.getMethod() != null ? log.getMethod() : "");
                row.createCell(4).setCellValue(log.getPath() != null ? log.getPath() : "");
                row.createCell(5).setCellValue(log.getParams() != null ? log.getParams() : "");
                row.createCell(6).setCellValue(log.getStatusCode() != null ? log.getStatusCode() : 0);
                row.createCell(7).setCellValue(log.getSuccess() != null && log.getSuccess() == 1 ? "成功" : "失败");
                row.createCell(8).setCellValue(log.getCostMs() != null ? log.getCostMs() : 0);
                row.createCell(9).setCellValue(log.getIp() != null ? log.getIp() : "");
                row.createCell(10).setCellValue(log.getErrorMsg() != null ? log.getErrorMsg() : "");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
