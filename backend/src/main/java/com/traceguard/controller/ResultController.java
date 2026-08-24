package com.traceguard.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.traceguard.common.BusinessException;
import com.traceguard.common.Result;
import com.traceguard.entity.*;
import com.traceguard.service.AuditService;
import com.traceguard.service.ExportService;
import com.traceguard.service.ProjectService;
import com.traceguard.service.ResultService;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/result")
@Api(tags = "06-分析结果查询")
public class ResultController {

    private static final Logger log = LoggerFactory.getLogger(ResultController.class);

    @Autowired
    private ResultService resultService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ExportService exportService;

    @Autowired
    private AuditService auditService;

    private static final DateTimeFormatter VECTOR_DTF = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** CQ-10：统一构建附件下载响应（消除多份复制粘贴，文件名经 URLEncoder 安全编码） */
    private ResponseEntity<byte[]> downloadResponse(byte[] data, String filename, MediaType contentType) {
        String encodedName;
        try {
            encodedName = URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 由 JDK 强制支持，理论上不可达；防御性回退避免受检异常扩散
            encodedName = filename.replaceAll(" ", "%20");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(contentType)
                .contentLength(data.length)
                .body(data);
    }

    /**
     * 导出语义向量（GAP-004，FR-CODE-003 业务规则 3）：遍历任务关联的需求与代码单元向量。
     * format=json -> [{type,id,dim,vector[]}]；format=csv -> type,id,dim,vector(分号分隔)。
     * 权限复用项目归属校验；导出操作写入审计日志。
     */
    @ApiOperation(value = "导出语义向量", notes = "GAP-004 导出任务关联的需求与代码单元语义向量，支持 format=json|csv；校验项目归属权限")
    @GetMapping("/{taskId}/vectors/export")
    public ResponseEntity<byte[]> exportSemanticVectors(@PathVariable Long taskId,
                                                         @RequestParam(defaultValue = "json") String format) throws Exception {
        Long projectId = exportService.getTaskProjectId(taskId);
        if (projectId == null) {
            throw new BusinessException(404, "任务不存在");
        }
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportSemanticVectors(taskId, format);
        String ext = "csv".equalsIgnoreCase(format) ? "csv" : "json";
        String filename = "语义向量_" + VECTOR_DTF.format(LocalDateTime.now()) + "." + ext;
        String contentType = "csv".equalsIgnoreCase(format) ? "text/csv" : "application/json";
        try {
            auditService.record(UserContext.getUserId(), UserContext.getUsername(), "导出语义向量",
                    "GET", "/api/result/" + taskId + "/vectors/export",
                    "format=" + format, null, 200, null, null);
        } catch (Exception ignored) {
            // CQ-04/SEC-14：审计写入失败不阻断导出，但必须可观测
            log.warn("导出语义向量审计记录写入失败", ignored);
        }
        return downloadResponse(data, filename, MediaType.parseMediaType(contentType));
    }

    /**
     * FR-CODE-003 规则3（2.5 整改项）：按项目导出语义向量（前端结果页/代码视图入口，无需 taskId）。
     * 权限复用项目归属校验；导出操作写入审计日志。
     */
    @ApiOperation(value = "导出语义向量（按项目）", notes = "FR-CODE-003 2.5 按项目导出需求与代码单元语义向量 JSON/CSV；校验项目归属权限")
    @GetMapping("/vectors/export/{projectId}")
    public ResponseEntity<byte[]> exportProjectSemanticVectors(@PathVariable Long projectId,
                                                               @RequestParam(defaultValue = "json") String format) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportSemanticVectorsByProject(projectId, format);
        String ext = "csv".equalsIgnoreCase(format) ? "csv" : "json";
        String filename = "语义向量_" + VECTOR_DTF.format(LocalDateTime.now()) + "." + ext;
        String contentType = "csv".equalsIgnoreCase(format) ? "text/csv" : "application/json";
        try {
            auditService.record(UserContext.getUserId(), UserContext.getUsername(), "导出语义向量",
                    "GET", "/api/result/vectors/export/" + projectId,
                    "format=" + format, null, 200, null, null);
        } catch (Exception ignored) {
            log.warn("导出语义向量（按项目）审计记录写入失败", ignored);
        }
        return downloadResponse(data, filename, MediaType.parseMediaType(contentType));
    }

    /**
     * 导出需求质量报告（FR-REQ-002 2.2 整改项）：结构化歧义检测 JSON（含类型/严重度/原文片段/建议），
     * 供审计导出。权限复用项目归属校验，导出操作写入审计日志。
     */
    @ApiOperation(value = "导出需求质量报告", notes = "FR-REQ-002 2.2 导出结构化歧义/矛盾/边界检测报告 JSON；校验项目归属权限并记录审计日志")
    @GetMapping("/requirements/{projectId}/quality/export")
    public ResponseEntity<byte[]> exportRequirementQuality(@PathVariable Long projectId) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportRequirementQuality(projectId);
        String filename = "需求质量报告_" + VECTOR_DTF.format(LocalDateTime.now()) + ".json";
        try {
            auditService.record(UserContext.getUserId(), UserContext.getUsername(), "导出需求质量报告",
                    "GET", "/api/result/requirements/" + projectId + "/quality/export",
                    "projectId=" + projectId, null, 200, null, null);
        } catch (Exception ignored) {
            log.warn("导出需求质量报告审计记录写入失败", ignored);
        }
        return downloadResponse(data, filename, MediaType.APPLICATION_JSON);
    }

    @ApiOperation(value = "查询项目需求列表", notes = "校验当前用户对项目的归属权限后返回全部需求")
    @GetMapping("/requirements/{projectId}")
    public Result<List<Requirement>> getRequirements(@PathVariable Long projectId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getRequirements(projectId));
    }

    @ApiOperation(value = "分页查询项目需求", notes = "pageNum 默认 1、pageSize 默认 10；校验项目归属权限")
    @GetMapping("/requirements/page/{projectId}")
    public Result<IPage<Requirement>> getRequirementsPage(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getRequirementsPage(projectId, pageNum, pageSize));
    }

    @ApiOperation(value = "按ID查询需求详情", notes = "需求存在时校验其所属项目的归属权限")
    @GetMapping("/requirement/{id}")
    public Result<Requirement> getRequirement(@PathVariable Long id) {
        Requirement requirement = resultService.getRequirementById(id);
        if (requirement != null) {
            projectService.checkOwnership(requirement.getProjectId());
        }
        return Result.success(requirement);
    }

    @ApiOperation(value = "查询需求对应的形式化规约", notes = "按需求ID查询Alloy形式化规约，存在时校验项目归属权限")
    @GetMapping("/spec/{requirementId}")
    public Result<FormalSpecification> getSpec(@PathVariable Long requirementId) {
        FormalSpecification spec = resultService.getSpecByRequirementId(requirementId);
        if (spec != null) {
            projectService.checkOwnership(spec.getProjectId());
        }
        return Result.success(spec);
    }

    /** 更新Alloy规约代码（FR-REQ-003 在线编辑与手动优化） */
    @ApiOperation(value = "更新Alloy规约代码", notes = "FR-REQ-003 在线编辑与手动优化；请求体传 alloyCode 字段；校验项目归属权限")
    @PostMapping("/spec/update/{specId}")
    public Result<FormalSpecification> updateSpec(@PathVariable Long specId,
                                                  @RequestBody Map<String, String> body) {
        FormalSpecification exist = resultService.getSpecById(specId);
        if (exist != null) {
            projectService.checkOwnership(exist.getProjectId());
        }
        return Result.success(resultService.updateSpec(specId, body.get("alloyCode")));
    }

    @ApiOperation(value = "查询项目代码单元列表", notes = "校验项目归属权限")
    @GetMapping("/codeunits/{projectId}")
    public Result<List<CodeUnit>> getCodeUnits(@PathVariable Long projectId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getCodeUnits(projectId));
    }

    @ApiOperation(value = "分页查询项目代码单元", notes = "pageNum 默认 1、pageSize 默认 10；校验项目归属权限")
    @GetMapping("/codeunits/page/{projectId}")
    public Result<IPage<CodeUnit>> getCodeUnitsPage(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getCodeUnitsPage(projectId, pageNum, pageSize));
    }

    @ApiOperation(value = "查询一致性校验结果", notes = "可按 taskId 过滤指定分析任务的结果；校验项目归属权限")
    @GetMapping("/consistency/{projectId}")
    public Result<List<ConsistencyResult>> getConsistencyResults(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getConsistencyResults(projectId, taskId));
    }

    @ApiOperation(value = "分页查询一致性校验结果", notes = "可按 taskId 过滤；pageNum 默认 1、pageSize 默认 10；校验项目归属权限")
    @GetMapping("/consistency/page/{projectId}")
    public Result<IPage<ConsistencyResult>> getConsistencyResultsPage(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getConsistencyResultsPage(projectId, taskId, pageNum, pageSize));
    }

    @ApiOperation(value = "查询缺陷列表", notes = "可按 taskId、缺陷级别 level、主类型 type（GAP-020 4类口径）、子类型 subType 与状态 status（GAP-011）过滤；校验项目归属权限")
    @GetMapping("/defects/{projectId}")
    public Result<List<Defect>> getDefects(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) String status) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getDefects(projectId, taskId, level, type, subType, status));
    }

    @ApiOperation(value = "分页查询缺陷列表", notes = "可按 taskId、缺陷级别 level、主类型 type（GAP-020 4类口径）、子类型 subType 与状态 status（GAP-011）过滤；pageNum 默认 1、pageSize 默认 10；校验项目归属权限")
    @GetMapping("/defects/page/{projectId}")
    public Result<IPage<Defect>> getDefectsPage(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getDefectsPage(projectId, taskId, level, type, subType, status, pageNum, pageSize));
    }

    @ApiOperation(value = "查询代码质量缺陷列表", notes = "可按 taskId 过滤；校验项目归属权限")
    @GetMapping("/code-defects/{projectId}")
    public Result<List<CodeDefect>> getCodeDefects(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getCodeDefects(projectId, taskId));
    }

    @ApiOperation(value = "分页查询代码质量缺陷", notes = "可按 taskId 过滤；pageNum 默认 1、pageSize 默认 10；校验项目归属权限")
    @GetMapping("/code-defects/page/{projectId}")
    public Result<IPage<CodeDefect>> getCodeDefectsPage(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getCodeDefectsPage(projectId, taskId, pageNum, pageSize));
    }

    @ApiOperation(value = "查询项目统计信息", notes = "返回项目维度的汇总统计数据；校验项目归属权限")
    @GetMapping("/statistics/{projectId}")
    public Result<Map<String, Object>> getStatistics(@PathVariable Long projectId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getProjectStatistics(projectId));
    }

    /**
     * 2.7 整改（FR-PLAT-003）：项目质量趋势时间序列。
     * 返回按任务结束时间升序的质量指标序列（覆盖率/平均相似度/缺陷数/严重缺陷/代码质量分），用于折线趋势图。
     */
    @ApiOperation(value = "查询项目质量趋势", notes = "2.7 返回已完成任务按时间排序的质量指标时间序列；校验项目归属权限")
    @GetMapping("/quality-trend/{projectId}")
    public Result<List<Map<String, Object>>> getQualityTrend(@PathVariable Long projectId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getQualityTrend(projectId));
    }

    @ApiOperation(value = "查询正向追溯矩阵", notes = "需求到代码单元的正向追溯关系；校验项目归属权限")
    @GetMapping("/traceability/{projectId}")
    public Result<List<Map<String, Object>>> getTraceabilityMatrix(@PathVariable Long projectId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getTraceabilityMatrix(projectId));
    }

    /** GAP-026：正向追溯矩阵分页——支持大数据量远程分页加载 */
    @ApiOperation(value = "GAP-026 分页查询正向追溯矩阵", notes = "pageNum 默认 1、pageSize 默认 20；校验项目归属权限")
    @GetMapping("/traceability/page/{projectId}")
    public Result<IPage<Map<String, Object>>> getTraceabilityMatrixPage(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getTraceabilityMatrixPage(projectId, pageNum, pageSize));
    }

    /** 反向追溯矩阵：代码单元 -> 最佳匹配需求（FR-TRACE-002） */
    @ApiOperation(value = "查询反向追溯矩阵", notes = "FR-TRACE-002 代码单元到最佳匹配需求的反向追溯；校验项目归属权限")
    @GetMapping("/traceability/reverse/{projectId}")
    public Result<List<Map<String, Object>>> getReverseTraceabilityMatrix(@PathVariable Long projectId) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getReverseTraceabilityMatrix(projectId));
    }

    /** GAP-026：反向追溯矩阵分页——支持大数据量远程分页加载 */
    @ApiOperation(value = "GAP-026 分页查询反向追溯矩阵", notes = "pageNum 默认 1、pageSize 默认 20；校验项目归属权限")
    @GetMapping("/traceability/reverse/page/{projectId}")
    public Result<IPage<Map<String, Object>>> getReverseTraceabilityMatrixPage(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        projectService.checkOwnership(projectId);
        return Result.success(resultService.getReverseTraceabilityMatrixPage(projectId, pageNum, pageSize));
    }

    /** 多项目对比统计（FR-PLAT-003） */
    @ApiOperation(value = "多项目对比统计", notes = "FR-PLAT-003 传入 projectIds 列表，逐个校验项目归属权限后返回对比统计")
    @GetMapping("/compare")
    public Result<List<Map<String, Object>>> compareProjects(@RequestParam List<Long> projectIds) {
        for (Long projectId : projectIds) {
            projectService.checkOwnership(projectId);
        }
        return Result.success(resultService.compareProjects(projectIds));
    }

    // ==================== GAP-011：缺陷状态流转 ====================

    /**
     * GAP-011：更新缺陷状态
     * body: {"status": "processing"} 或 {"status": "resolved", ...}
     */
    @ApiOperation(value = "GAP-011 更新缺陷状态", notes = "PUT /result/defect/{defectId}/status body:{\"status\":\"processing\"|\"resolved\"|\"ignored\"}；含合法流转校验与审计日志")
    @PutMapping("/defect/{defectId}/status")
    public Result<Defect> updateDefectStatus(
            @PathVariable Long defectId,
            @RequestBody Map<String, String> body) {
        // 归属校验：查询缺陷所属项目并校验权限
        Defect defect = resultService.getDefectById(defectId);
        if (defect == null) {
            throw new BusinessException(404, "缺陷不存在");
        }
        projectService.checkOwnership(defect.getProjectId());

        String status = body.get("status");
        if (status == null || status.isEmpty()) {
            throw new BusinessException(400, "status 参数不能为空");
        }
        Long userId = UserContext.getUserId();
        return Result.success(resultService.updateDefectStatus(defectId, status, userId));
    }
}
