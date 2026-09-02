package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.service.AuditService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 审计日志查询接口（仅管理员）
 */
@RestController
@RequestMapping("/audit")
@Api(tags = "09-审计日志")
public class AuditController {

    @Autowired
    private AuditService auditService;

    @ApiOperation(value = "分页查询审计日志", notes = "仅管理员可查看（无权限返回403）；支持按 keyword 过滤，pageNum 默认 1、pageSize 默认 10")
    @GetMapping("/page")
    public Result<Map<String, Object>> page(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) String keyword) {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可查看审计日志");
        }
        return Result.success(auditService.page(pageNum, pageSize, keyword));
    }

    /** 校验审计日志哈希链完整性（AUD-08 防篡改）：返回 total/valid/firstBrokenIndex 等 */
    /** 我的操作足迹（个性化增强 BATCH-5）：登录用户查自己的最近操作，无需管理员权限 */
    @ApiOperation(value = "我的操作足迹", notes = "个性化增强 BATCH-5：当前登录用户最近操作（默认 20 条，上限 50），仅本人可见")
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(java.util.Collections.singletonMap(
                "records", auditService.listMine(UserContext.getUserId(), limit)));
    }

    /** 校验审计日志哈希链完整性（AUD-08 防篡改）：返回 total/valid/firstBrokenIndex 等 */
    @ApiOperation(value = "校验审计日志哈希链完整性", notes = "AUD-08，仅管理员可用；逐条重算 SHA-256 并与存储值比对，定位首条异常记录")
    @GetMapping("/verify")
    public Result<Map<String, Object>> verify() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可校验审计日志");
        }
        return Result.success(auditService.verifyChain());
    }

    /** 导出审计日志Excel（FR-PLAT-004），支持按关键字过滤 */
    @ApiOperation(value = "导出审计日志Excel", notes = "FR-PLAT-004，仅管理员可导出（无权限抛出403业务异常）；支持按 keyword 过滤，返回 .xlsx 附件文件流")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword) throws Exception {
        if (!UserContext.isAdmin()) {
            throw new com.traceguard.common.BusinessException(403, "无权限：仅管理员可导出审计日志");
        }
        byte[] data = auditService.exportExcel(keyword);
        String filename = java.net.URLEncoder.encode(
                "审计日志_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx",
                "UTF-8").replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(data);
    }
}
