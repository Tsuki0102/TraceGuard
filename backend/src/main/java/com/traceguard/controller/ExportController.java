package com.traceguard.controller;

import com.traceguard.common.BusinessException;
import com.traceguard.common.ReportTemplate;
import com.traceguard.common.Result;
import com.traceguard.entity.ReportTemplateConfig;
import com.traceguard.service.ExportService;
import com.traceguard.service.ProjectService;
import com.traceguard.service.ReportTemplateService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报告导出接口
 * 支持：缺陷清单Excel、追溯矩阵Excel、代码缺陷Excel、综合报告Word/PDF、语义向量导出
 */
@RestController
@RequestMapping("/export")
@Api(tags = "07-报表导出")
public class ExportController {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Autowired
    private ExportService exportService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ReportTemplateService reportTemplateService;

    /**
     * 导出缺陷清单Excel
     */
    @ApiOperation(value = "导出缺陷清单Excel", notes = "返回 .xlsx 附件文件流，需以二进制流方式接收；校验项目归属权限")
    @GetMapping("/defects/excel/{projectId}")
    public ResponseEntity<byte[]> exportDefectsExcel(@PathVariable Long projectId) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportDefectsExcel(projectId);
        String filename = "缺陷清单_" + DTF.format(LocalDateTime.now()) + ".xlsx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * 导出追溯矩阵Excel
     */
    @ApiOperation(value = "导出追溯矩阵Excel", notes = "返回需求-代码双向追溯矩阵 .xlsx 附件文件流；校验项目归属权限")
    @GetMapping("/traceability/excel/{projectId}")
    public ResponseEntity<byte[]> exportTraceabilityExcel(@PathVariable Long projectId) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportTraceabilityExcel(projectId);
        String filename = "需求-代码双向追溯矩阵_" + DTF.format(LocalDateTime.now()) + ".xlsx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * 导出反向追溯矩阵Excel
     */
    @ApiOperation(value = "导出反向追溯矩阵Excel", notes = "FR-TRACE-002 代码到需求方向的反向追溯矩阵 .xlsx 附件文件流；校验项目归属权限")
    @GetMapping("/traceability/reverse/excel/{projectId}")
    public ResponseEntity<byte[]> exportReverseTraceabilityExcel(@PathVariable Long projectId) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportReverseTraceabilityExcel(projectId);
        String filename = "反向追溯矩阵_" + DTF.format(LocalDateTime.now()) + ".xlsx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * 导出代码质量缺陷Excel
     */
    @ApiOperation(value = "导出代码质量缺陷Excel", notes = "返回 .xlsx 附件文件流，需以二进制流方式接收；校验项目归属权限")
    @GetMapping("/code-defects/excel/{projectId}")
    public ResponseEntity<byte[]> exportCodeDefectsExcel(@PathVariable Long projectId) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportCodeDefectsExcel(projectId);
        String filename = "代码质量缺陷_" + DTF.format(LocalDateTime.now()) + ".xlsx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * 导出统计报表Excel（FR-PLAT-003）
     */
    @ApiOperation(value = "导出统计报表Excel", notes = "FR-PLAT-003 统计指标汇总与缺陷类型分布 .xlsx 附件文件流（两个Sheet）；校验项目归属权限")
    @GetMapping("/statistics/excel/{projectId}")
    public ResponseEntity<byte[]> exportStatisticsExcel(@PathVariable Long projectId) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data = exportService.exportStatisticsExcel(projectId);
        String filename = "统计报表_" + DTF.format(LocalDateTime.now()) + ".xlsx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * 导出多项目对比统计Excel（FR-PLAT-003）
     */
    @ApiOperation(value = "导出多项目对比统计Excel", notes = "FR-PLAT-003 传入 projectIds 列表，逐个校验项目归属权限后导出对比统计 .xlsx 附件文件流")
    @GetMapping("/compare/excel")
    public ResponseEntity<byte[]> exportCompareExcel(@RequestParam List<Long> projectIds) throws Exception {
        for (Long projectId : projectIds) {
            projectService.checkOwnership(projectId);
        }
        byte[] data = exportService.exportCompareExcel(projectIds);
        String filename = "多项目对比统计_" + DTF.format(LocalDateTime.now()) + ".xlsx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * 导出综合分析报告Word
     * templateId 优先；若无则按 template 枚举（兼容旧接口）；否则用默认模板
     */
    @ApiOperation(value = "导出综合分析报告Word", notes = "FR-CHECK-005 templateId 指定自定义模板（优先），template 可选 FULL/DEFECT_ONLY/BRIEF 预置模板；返回 .docx 附件文件流；校验项目归属权限")
    @GetMapping("/report/word/{projectId}")
    public ResponseEntity<byte[]> exportReportWord(@PathVariable Long projectId,
                                                   @RequestParam(required = false) Long templateId,
                                                   @RequestParam(required = false) ReportTemplate template) throws Exception {
        projectService.checkOwnership(projectId);
        byte[] data;
        if (templateId != null) {
            // 细粒度数据权限：普通用户仅可用系统模板或自己的模板导出
            reportTemplateService.checkTemplateAccess(templateId);
            ReportTemplateConfig config = reportTemplateService.getById(templateId);
            data = exportService.exportReportWord(projectId, config);
        } else if (template != null) {
            data = exportService.exportReportWord(projectId, template);
        } else {
            // 细粒度数据权限：默认模板不可见时（他人私有模板）回退系统 FULL
            ReportTemplateConfig defaultConfig = reportTemplateService.resolveDefaultForExport();
            data = defaultConfig != null ? exportService.exportReportWord(projectId, defaultConfig)
                    : exportService.exportReportWord(projectId, ReportTemplate.FULL);
        }
        String filename = "一致性校验与缺陷检测报告_" + DTF.format(LocalDateTime.now()) + ".docx";
        return buildFileResponse(data, filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    /**
     * 导出综合分析报告PDF
     * templateId 优先；若无则按 template 枚举（兼容旧接口）；否则用默认模板
     */
    @ApiOperation(value = "导出综合分析报告PDF", notes = "FR-CHECK-005 templateId 指定自定义模板（优先），template 可选 FULL/DEFECT_ONLY/BRIEF 预置模板；返回 .pdf 附件文件流；校验项目归属权限")
    @GetMapping("/report/pdf/{projectId}")
    public ResponseEntity<byte[]> exportReportPdf(@PathVariable Long projectId,
                                                  @RequestParam(required = false) Long templateId,
                                                  @RequestParam(required = false) ReportTemplate template) throws Exception {
        projectService.checkOwnership(projectId);
        try {
            byte[] data;
            if (templateId != null) {
                // 细粒度数据权限：普通用户仅可用系统模板或自己的模板导出
                reportTemplateService.checkTemplateAccess(templateId);
                ReportTemplateConfig config = reportTemplateService.getById(templateId);
                data = exportService.exportReportPdf(projectId, config);
            } else if (template != null) {
                data = exportService.exportReportPdf(projectId, template);
            } else {
                // 细粒度数据权限：默认模板不可见时（他人私有模板）回退系统 FULL
                ReportTemplateConfig defaultConfig = reportTemplateService.resolveDefaultForExport();
                data = defaultConfig != null ? exportService.exportReportPdf(projectId, defaultConfig)
                        : exportService.exportReportPdf(projectId, ReportTemplate.FULL);
            }
            String filename = "一致性校验与缺陷检测报告_" + DTF.format(LocalDateTime.now()) + ".pdf";
            return buildFileResponse(data, filename, MediaType.APPLICATION_PDF_VALUE);
        } catch (IllegalStateException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    // ==================== GAP-010：报告模板 CRUD 接口 ====================

    @ApiOperation(value = "查询所有报告模板", notes = "GAP-010 返回全部模板（系统+用户），按 sort 升序")
    @GetMapping("/report-templates")
    public Result<List<ReportTemplateConfig>> listTemplates() {
        return Result.success(reportTemplateService.listAll());
    }

    @ApiOperation(value = "创建报告模板", notes = "GAP-010 用户自定义模板，系统模板不可通过此接口创建")
    @PostMapping("/report-templates")
    public Result<ReportTemplateConfig> createTemplate(@RequestBody ReportTemplateConfig config) {
        return Result.success(reportTemplateService.create(config));
    }

    @ApiOperation(value = "编辑报告模板", notes = "GAP-010 仅可编辑用户模板，系统模板不可修改")
    @PutMapping("/report-templates/{id}")
    public Result<ReportTemplateConfig> updateTemplate(@PathVariable Long id,
                                                       @RequestBody ReportTemplateConfig config) {
        return Result.success(reportTemplateService.update(id, config));
    }

    @ApiOperation(value = "删除报告模板", notes = "GAP-010 仅可删除用户模板，系统模板不可删除；删除默认模板时自动回退 FULL")
    @DeleteMapping("/report-templates/{id}")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        reportTemplateService.delete(id);
        return Result.success();
    }

    @ApiOperation(value = "设为默认模板", notes = "GAP-010 全局唯一默认，设置后原默认取消")
    @PutMapping("/report-templates/{id}/default")
    public Result<Void> setDefaultTemplate(@PathVariable Long id) {
        reportTemplateService.setDefault(id);
        return Result.success();
    }

    /**
     * 构建文件下载响应（中文文件名需URL编码）
     */
    private ResponseEntity<byte[]> buildFileResponse(byte[] data, String filename, String contentType) throws Exception {
        String encodedName = URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }
}
