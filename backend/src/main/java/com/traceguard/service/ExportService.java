package com.traceguard.service;

import com.traceguard.common.ReportTemplate;
import com.traceguard.entity.*;
import com.traceguard.mapper.AnalysisTaskMapper;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.service.report.*;
import com.traceguard.util.SemanticVectorUtil;
import com.traceguard.util.SemanticVectorUtil.SemanticVector;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报告导出服务
 * 支持：缺陷清单Excel、正向/反向追溯矩阵Excel、统计报表Excel、多项目对比Excel、代码缺陷Excel、综合报告Word/PDF
 * FR-CHECK-005：综合报告支持 FULL/DEFECT_ONLY/BRIEF 三种预置模板
 */
@Service
public class ExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportService.class);

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;

    @Autowired
    private ResultService resultService;

    /** GAP-010：章节渲染器注册表（Spring 注入全部 Renderer 实现自动注册） */
    @Autowired
    private List<ReportSectionRenderer> sectionRenderers;

    /** 渲染器查找表：sectionKey -> renderer */
    private Map<String, ReportSectionRenderer> rendererRegistry = new HashMap<>();

    @PostConstruct
    public void initRendererRegistry() {
        for (ReportSectionRenderer renderer : sectionRenderers) {
            rendererRegistry.put(renderer.key(), renderer);
            LOGGER.info("注册报告章节渲染器: {} -> {}", renderer.key(), renderer.getClass().getSimpleName());
        }
    }

    /**
     * 导出缺陷清单Excel
     */
    public byte[] exportDefectsExcel(Long projectId) throws Exception {
        List<Defect> defects = resultService.getDefects(projectId, null, null);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("缺陷清单");

            // GAP-020：表头含子类型列；GAP-011：增加状态列；2.6 整改：增加缺陷行号列
            String[] headers = {"缺陷ID", "缺陷类型", "子类型", "状态", "缺陷等级", "缺陷行号", "相关需求原文", "相关代码片段", "缺陷原因", "修复建议"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                XSSFCell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            int rowIndex = 1;
            for (Defect d : defects) {
                XSSFRow row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(d.getDefectId());
                row.createCell(1).setCellValue(nullToEmpty(d.getDefectType()));
                row.createCell(2).setCellValue(nullToEmpty(d.getSubType()));
                row.createCell(3).setCellValue(nullToEmpty(d.getStatus()) + (d.getStatus() != null ? (" - " + com.traceguard.common.DefectStatus.labelOf(d.getStatus())) : ""));
                row.createCell(4).setCellValue("serious".equals(d.getDefectLevel()) ? "严重" : "一般");
                if (d.getDefectLine() != null) {
                    row.createCell(5).setCellValue(d.getDefectLine());
                } else {
                    row.createCell(5).setCellValue("未定位");
                }
                row.createCell(6).setCellValue(truncate(nullToEmpty(d.getRequirementText()), 500));
                row.createCell(7).setCellValue(truncate(nullToEmpty(d.getCodeSnippet()), 500));
                row.createCell(8).setCellValue(truncate(nullToEmpty(d.getDefectReason()), 500));
                row.createCell(9).setCellValue(truncate(nullToEmpty(d.getRepairSuggestion()), 500));
            }
            // 自动列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 2000, 15000));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出追溯矩阵Excel（正向：需求->代码）
     */
    public byte[] exportTraceabilityExcel(Long projectId) throws Exception {
        List<Map<String, Object>> matrix = resultService.getTraceabilityMatrix(projectId);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("需求-代码双向追溯矩阵");

            // FR-CHECK-004：正向矩阵含代码文件与代码行号两列
            String[] headers = {"序号", "需求ID", "需求原文", "一致性状态", "相似度", "代码文件", "代码行号", "缺陷等级", "修复建议"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowIndex = 1;
            for (Map<String, Object> row : matrix) {
                XSSFRow dataRow = sheet.createRow(rowIndex++);
                dataRow.createCell(0).setCellValue(rowIndex - 1);
                dataRow.createCell(1).setCellValue(String.valueOf(row.getOrDefault("requirementId", "")));
                dataRow.createCell(2).setCellValue(truncate(String.valueOf(row.getOrDefault("requirementText", "")), 500));
                dataRow.createCell(3).setCellValue(mapStatusText(row));
                Object sim = row.get("similarity");
                dataRow.createCell(4).setCellValue(sim != null ? String.format("%.1f%%", ((Double) sim) * 100) : "-");
                dataRow.createCell(5).setCellValue(String.valueOf(row.getOrDefault("filePath", "-")));
                Object startLine = row.get("startLine");
                dataRow.createCell(6).setCellValue(startLine != null ? String.valueOf(startLine) : "-");
                dataRow.createCell(7).setCellValue(mapLevelText(row));
                dataRow.createCell(8).setCellValue(truncate(String.valueOf(row.getOrDefault("repairSuggestion", "-")), 500));
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 2000, 15000));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出反向追溯矩阵Excel（FR-TRACE-002 代码->需求方向，C6）
     */
    public byte[] exportReverseTraceabilityExcel(Long projectId) throws Exception {
        List<Map<String, Object>> matrix = resultService.getReverseTraceabilityMatrix(projectId);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("反向追溯矩阵");

            String[] headers = {"序号", "代码文件", "类名", "方法名", "起始行", "追溯状态", "最佳匹配需求ID", "相似度", "缺陷等级", "修复建议"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowIndex = 1;
            for (Map<String, Object> row : matrix) {
                XSSFRow dataRow = sheet.createRow(rowIndex++);
                dataRow.createCell(0).setCellValue(rowIndex - 1);
                dataRow.createCell(1).setCellValue(String.valueOf(row.getOrDefault("filePath", "-")));
                dataRow.createCell(2).setCellValue(String.valueOf(row.getOrDefault("className", "-")));
                dataRow.createCell(3).setCellValue(String.valueOf(row.getOrDefault("methodName", "-")));
                dataRow.createCell(4).setCellValue(row.get("startLine") != null ? String.valueOf(row.get("startLine")) : "-");
                dataRow.createCell(5).setCellValue(mapReverseStatusText(row));
                dataRow.createCell(6).setCellValue(String.valueOf(row.getOrDefault("requirementId", "-")));
                Object sim = row.get("similarity");
                dataRow.createCell(7).setCellValue(sim != null ? String.format("%.1f%%", ((Double) sim) * 100) : "-");
                dataRow.createCell(8).setCellValue(mapLevelText(row));
                dataRow.createCell(9).setCellValue(truncate(String.valueOf(row.getOrDefault("repairSuggestion", "-")), 500));
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 2000, 15000));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出代码质量缺陷Excel
     */
    public byte[] exportCodeDefectsExcel(Long projectId) throws Exception {
        List<CodeDefect> codeDefects = resultService.getCodeDefects(projectId, null);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("代码质量缺陷");

            String[] headers = {"文件路径", "类名", "方法名", "行号", "缺陷类型", "严重程度", "描述", "修复建议"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowIndex = 1;
            for (CodeDefect d : codeDefects) {
                XSSFRow row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(nullToEmpty(d.getFilePath()));
                row.createCell(1).setCellValue(nullToEmpty(d.getClassName()));
                row.createCell(2).setCellValue(nullToEmpty(d.getMethodName()));
                row.createCell(3).setCellValue(d.getLineNumber() != null ? d.getLineNumber() : 0);
                row.createCell(4).setCellValue(nullToEmpty(d.getDefectType()));
                row.createCell(5).setCellValue(nullToEmpty(d.getSeverity()));
                row.createCell(6).setCellValue(truncate(nullToEmpty(d.getDescription()), 300));
                row.createCell(7).setCellValue(truncate(nullToEmpty(d.getRepairSuggestion()), 300));
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 2000, 15000));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出统计报表Excel（FR-PLAT-003，C7）：Sheet1 统计指标汇总 + Sheet2 缺陷类型分布
     */
    public byte[] exportStatisticsExcel(Long projectId) throws Exception {
        Map<String, Object> stats = resultService.getProjectStatistics(projectId);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("统计指标");
            XSSFRow headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("统计指标");
            headerRow.createCell(1).setCellValue("数值");
            String[][] metrics = {
                    {"需求总数", toStatString(stats.get("totalRequirements"))},
                    {"需求覆盖率(%)", toStatString(stats.get("coverageRate"))},
                    {"需求-代码缺陷总数", toStatString(stats.get("totalDefects"))},
                    {"严重缺陷数", toStatString(stats.get("seriousDefects"))},
                    {"一般缺陷数", toStatString(stats.get("generalDefects"))},
                    {"代码基础缺陷数", toStatString(stats.get("codeDefects"))},
                    {"高危代码缺陷数", toStatString(stats.get("highCodeDefects"))},
                    {"中危代码缺陷数", toStatString(stats.get("mediumCodeDefects"))},
                    {"代码质量评分", toStatString(stats.get("codeQualityScore"))}
            };
            int rowIndex = 1;
            for (String[] metric : metrics) {
                XSSFRow row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(metric[0]);
                row.createCell(1).setCellValue(metric[1]);
            }

            XSSFSheet typeSheet = workbook.createSheet("缺陷类型分布");
            XSSFRow typeHeader = typeSheet.createRow(0);
            typeHeader.createCell(0).setCellValue("缺陷类型");
            typeHeader.createCell(1).setCellValue("数量");
            int typeRowIndex = 1;
            Object typeDist = stats.get("defectTypeDistribution");
            if (typeDist instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) typeDist).entrySet()) {
                    XSSFRow row = typeSheet.createRow(typeRowIndex++);
                    row.createCell(0).setCellValue(String.valueOf(entry.getKey()));
                    row.createCell(1).setCellValue(String.valueOf(entry.getValue()));
                }
            }
            if (typeRowIndex == 1) {
                typeSheet.createRow(1).createCell(0).setCellValue("暂无缺陷类型分布数据");
            }

            for (int i = 0; i < 2; i++) {
                sheet.autoSizeColumn(i);
                typeSheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出多项目对比统计Excel（FR-PLAT-003，C7）
     */
    public byte[] exportCompareExcel(List<Long> projectIds) throws Exception {
        List<Map<String, Object>> rows = resultService.compareProjects(projectIds);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("多项目对比统计");

            String[] headers = {"项目ID", "项目名称", "行业类型", "需求总数", "需求覆盖率(%)", "缺陷总数",
                    "严重缺陷", "一般缺陷", "代码基础缺陷数", "高危代码缺陷", "中危代码缺陷", "代码质量评分"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                XSSFRow dataRow = sheet.createRow(rowIndex++);
                dataRow.createCell(0).setCellValue(toStatString(row.get("projectId")));
                dataRow.createCell(1).setCellValue(String.valueOf(row.getOrDefault("projectName", "-")));
                dataRow.createCell(2).setCellValue(String.valueOf(row.getOrDefault("industryType", "-")));
                dataRow.createCell(3).setCellValue(toStatString(row.get("totalRequirements")));
                dataRow.createCell(4).setCellValue(toStatString(row.get("coverageRate")));
                dataRow.createCell(5).setCellValue(toStatString(row.get("totalDefects")));
                dataRow.createCell(6).setCellValue(toStatString(row.get("seriousDefects")));
                dataRow.createCell(7).setCellValue(toStatString(row.get("generalDefects")));
                dataRow.createCell(8).setCellValue(toStatString(row.get("codeDefects")));
                dataRow.createCell(9).setCellValue(toStatString(row.get("highCodeDefects")));
                dataRow.createCell(10).setCellValue(toStatString(row.get("mediumCodeDefects")));
                dataRow.createCell(11).setCellValue(toStatString(row.get("codeQualityScore")));
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 2000, 15000));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出综合分析报告Word文档（旧枚举入口，委托到对应系统模板）
     * FR-CHECK-005：template 预置模板控制章节取舍
     */
    public byte[] exportReportWord(Long projectId, ReportTemplate template) throws Exception {
        return exportReportWord(projectId, templateConfigFromEnum(template));
    }

    /**
     * GAP-010：导出综合分析报告Word文档（基于模板配置，注册表化渲染）
     * @param projectId 项目ID
     * @param template 报告模板配置（sections JSON 定义章节顺序与标题）
     */
    public byte[] exportReportWord(Long projectId, ReportTemplateConfig template) throws Exception {
        ReportContext ctx = buildReportContext(projectId);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 报告主标题
            String reportTitle = (template.getTitle() != null && !template.getTitle().isEmpty())
                    ? template.getTitle() : "软件需求-代码一致性校验与缺陷检测报告";
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText(reportTitle);
            titleRun.setBold(true);
            titleRun.setFontSize(20);

            // 4.4 整改：封面副标题（报告排版自定义）
            if (template.getSubtitle() != null && !template.getSubtitle().isEmpty()) {
                XWPFParagraph sub = doc.createParagraph();
                sub.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun subRun = sub.createRun();
                subRun.setText(template.getSubtitle());
                subRun.setFontSize(13);
                subRun.setColor("595959");
            }

            // 4.4 整改：页眉文本（报告排版自定义，空=系统默认）
            String headerText = (template.getHeaderText() != null && !template.getHeaderText().isEmpty())
                    ? template.getHeaderText() : reportTitle;
            addDocHeader(doc, headerText);

            // 按模板 sections 顺序渲染各章节
            List<SectionConfig> sections = parseSections(template.getSections());
            int sectionNo = 0;
            for (SectionConfig sc : sections) {
                ReportSectionRenderer renderer = rendererRegistry.get(sc.key);
                if (renderer == null) {
                    LOGGER.warn("未知章节 key 跳过: {}", sc.key);
                    continue;
                }
                String sectionTitle = (sc.title != null && !sc.title.isEmpty()) ? sc.title : renderer.defaultTitle();
                renderer.renderWord(ctx, doc, sectionTitle, sectionNo + 1);
                sectionNo++;
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出综合分析报告PDF（旧枚举入口，委托到对应系统模板）
     * FR-CHECK-005：template 预置模板控制章节取舍
     */
    public byte[] exportReportPdf(Long projectId, ReportTemplate template) throws Exception {
        return exportReportPdf(projectId, templateConfigFromEnum(template));
    }

    /**
     * GAP-010：导出综合分析报告PDF（基于模板配置，注册表化渲染）
     * @param projectId 项目ID
     * @param template 报告模板配置
     */
    public byte[] exportReportPdf(Long projectId, ReportTemplateConfig template) throws Exception {
        ReportContext ctx = buildReportContext(projectId);

        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font font = loadChineseFont(doc);
            PdfCursorAdapter cursor = new PdfCursorAdapter(doc, font);

            // 报告主标题
            String reportTitle = (template.getTitle() != null && !template.getTitle().isEmpty())
                    ? template.getTitle() : "软件需求-代码一致性校验与缺陷检测报告";
            cursor.line(18, true, reportTitle);
            // 4.4 整改：封面副标题（报告排版自定义）
            if (template.getSubtitle() != null && !template.getSubtitle().isEmpty()) {
                cursor.line(12, false, template.getSubtitle());
            }
            cursor.line(12, false, "项目名称：" + nullToEmpty(ctx.project.getProjectName()));
            cursor.line(12, false, "报告生成时间：" + DTF.format(LocalDateTime.now()));
            cursor.gap();

            // 按模板 sections 顺序渲染各章节
            List<SectionConfig> sections = parseSections(template.getSections());
            int sectionNo = 0;
            for (SectionConfig sc : sections) {
                ReportSectionRenderer renderer = rendererRegistry.get(sc.key);
                if (renderer == null) {
                    LOGGER.warn("未知章节 key 跳过: {}", sc.key);
                    continue;
                }
                String sectionTitle = (sc.title != null && !sc.title.isEmpty()) ? sc.title : renderer.defaultTitle();
                renderer.renderPdf(ctx, cursor, sectionTitle, sectionNo + 1);
                sectionNo++;
            }

            cursor.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出语义向量（GAP-004，FR-CODE-003 业务规则 3）：遍历任务关联的需求与代码单元向量。
     * @param format json（[{type,id,dim,vector[]}]）或 csv（type,id,dim,vector分号分隔）
     */
    public byte[] exportSemanticVectors(Long taskId, String format) throws Exception {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        return exportSemanticVectorsByProject(task.getProjectId(), format);
    }

    /** FR-CODE-003 规则3（2.5 整改项）：按项目导出语义向量（前端结果页/代码视图入口，无需 taskId） */
    public byte[] exportSemanticVectorsByProject(Long projectId, String format) throws Exception {
        List<Requirement> reqs = resultService.getRequirements(projectId);
        List<CodeUnit> codes = resultService.getCodeUnits(projectId);
        if ("csv".equalsIgnoreCase(format)) {
            return exportVectorsCsv(reqs, codes);
        }
        return exportVectorsJson(reqs, codes);
    }

    /** 向量导出任务归属项目解析（供控制器做项目归属权限校验）；任务不存在返回 null */
    public Long getTaskProjectId(Long taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        return task != null ? task.getProjectId() : null;
    }

    private byte[] exportVectorsJson(List<Requirement> reqs, List<CodeUnit> codes) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Requirement req : reqs) {
            SemanticVector sv = SemanticVectorUtil.parse(req.getSemanticVector());
            if (!sv.hasVector()) continue;
            first = appendVectorJson(sb, first, "requirement", req.getRequirementId(), sv);
        }
        for (CodeUnit code : codes) {
            SemanticVector sv = SemanticVectorUtil.parse(code.getSemanticVector());
            if (!sv.hasVector()) continue;
            first = appendVectorJson(sb, first, "codeunit", code.getCodeId(), sv);
        }
        sb.append("]");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean appendVectorJson(StringBuilder sb, boolean first, String type, String id, SemanticVector sv) {
        if (!first) sb.append(",");
        sb.append("{\"type\":\"").append(type)
          .append("\",\"id\":\"").append(escapeJson(id))
          .append("\",\"dim\":").append(sv.getDim())
          .append(",\"vector\":[");
        float[] vec = sv.getVector();
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]}");
        return false;
    }

    private byte[] exportVectorsCsv(List<Requirement> reqs, List<CodeUnit> codes) throws Exception {
        StringBuilder sb = new StringBuilder("type,id,dim,vector\n");
        for (Requirement req : reqs) {
            SemanticVector sv = SemanticVectorUtil.parse(req.getSemanticVector());
            if (!sv.hasVector()) continue;
            sb.append("requirement,").append(req.getRequirementId()).append(",").append(sv.getDim()).append(",");
            appendVectorCsv(sb, sv.getVector());
            sb.append("\n");
        }
        for (CodeUnit code : codes) {
            SemanticVector sv = SemanticVectorUtil.parse(code.getSemanticVector());
            if (!sv.hasVector()) continue;
            sb.append("codeunit,").append(code.getCodeId() != null ? code.getCodeId() : "").append(",").append(sv.getDim()).append(",");
            appendVectorCsv(sb, sv.getVector());
            sb.append("\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void appendVectorCsv(StringBuilder sb, float[] vec) {
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(";");
            sb.append(vec[i]);
        }
    }

    /**
     * 导出需求质量报告（FR-REQ-002 2.2 整改项）：每条需求的需求ID/标题/原文/结构化歧义检测报告 JSON。
     * 质量报告字段若为结构化 JSON 则原样携带，兼容旧版单条文本（解析失败保留原文）。
     */
    public byte[] exportRequirementQuality(Long projectId) throws Exception {
        List<Requirement> reqs = resultService.getRequirements(projectId);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Requirement r : reqs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requirementId", r.getRequirementId());
            row.put("title", r.getTitle());
            row.put("originalText", r.getOriginalText());
            Object quality = r.getAmbiguityReport();
            if (r.getAmbiguityReport() != null && !r.getAmbiguityReport().isEmpty()) {
                try {
                    quality = om.readTree(r.getAmbiguityReport());
                } catch (Exception ignored) {
                    // 非 JSON（旧版单条文本）保留原样
                }
            }
            row.put("qualityReport", quality);
            rows.add(row);
        }
        return om.writeValueAsString(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 私有工具方法 ====================

    /**
     * GAP-010：构建报告上下文（一次查询，多章节复用）
     */
    private ReportContext buildReportContext(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在");
        }
        Map<String, Object> stats = resultService.getProjectStatistics(projectId);
        List<Defect> defects = resultService.getDefects(projectId, null, null);
        List<CodeDefect> codeDefects = resultService.getCodeDefects(projectId, null);
        List<Map<String, Object>> matrix = resultService.getTraceabilityMatrix(projectId);
        return new ReportContext(project, stats, defects, codeDefects, matrix);
    }

    /**
     * GAP-010：将旧枚举模板转为 ReportTemplateConfig（内存构造，不查库）
     */
    private ReportTemplateConfig templateConfigFromEnum(ReportTemplate template) {
        ReportTemplateConfig config = new ReportTemplateConfig();
        switch (template) {
            case FULL:
                config.setCode("FULL");
                config.setTemplateName("完整报告");
                config.setSections("[{\"key\":\"project-overview\",\"title\":\"\"},{\"key\":\"stats-summary\",\"title\":\"\"},{\"key\":\"defect-type-distribution\",\"title\":\"\"},{\"key\":\"defect-detail\",\"title\":\"\"},{\"key\":\"code-quality\",\"title\":\"\"},{\"key\":\"traceability-matrix\",\"title\":\"\"}]");
                break;
            case DEFECT_ONLY:
                config.setCode("DEFECT_ONLY");
                config.setTemplateName("缺陷聚焦报告");
                config.setSections("[{\"key\":\"project-overview\",\"title\":\"\"},{\"key\":\"stats-summary\",\"title\":\"\"},{\"key\":\"defect-detail\",\"title\":\"\"},{\"key\":\"code-quality\",\"title\":\"\"}]");
                break;
            case BRIEF:
                config.setCode("BRIEF");
                config.setTemplateName("简要报告");
                config.setSections("[{\"key\":\"project-overview\",\"title\":\"\"},{\"key\":\"stats-summary\",\"title\":\"\"}]");
                break;
        }
        config.setTitle("");
        return config;
    }

    /**
     * GAP-010：解析 sections JSON 为有序配置列表
     */
    @SuppressWarnings("unchecked")
    private List<SectionConfig> parseSections(String sectionsJson) {
        if (sectionsJson == null || sectionsJson.isEmpty()) {
            return Collections.emptyList();
        }
        List<SectionConfig> result = new ArrayList<>();
        try {
            // 简单 JSON 解析（避免引入 Jackson 依赖到工具方法）
            // 格式：[{"key":"...","title":"..."},...]
            String json = sectionsJson.trim();
            // 去除首尾方括号
            if (json.startsWith("[")) json = json.substring(1);
            if (json.endsWith("]")) json = json.substring(0, json.length() - 1);
            if (json.isEmpty()) return result;

            // 按对象分割
            int depth = 0;
            int start = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') { depth++; if (depth == 1) start = i; }
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String obj = json.substring(start, i + 1);
                        String key = extractJsonValue(obj, "key");
                        String title = extractJsonValue(obj, "title");
                        result.add(new SectionConfig(key, title));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("解析 sections JSON 失败，回退空列表: {}", e.getMessage());
        }
        return result;
    }

    /** 从 JSON 对象字符串中提取指定字段的值 */
    private String extractJsonValue(String json, String field) {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        idx = json.indexOf(":", idx + search.length());
        if (idx < 0) return "";
        idx++;
        // 跳过空白
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return "";
        if (json.charAt(idx) == '"') {
            // 字符串值
            int end = json.indexOf("\"", idx + 1);
            if (end < 0) return "";
            return json.substring(idx + 1, end);
        }
        // 非字符串值（数字、布尔等），取到逗号或结尾
        int end = idx;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(idx, end).trim();
    }

    /** GAP-010：章节配置内部值对象 */
    private static class SectionConfig {
        final String key;
        final String title;
        SectionConfig(String key, String title) {
            this.key = key;
            this.title = title;
        }
    }

    /**
     * 加载中文字体（GAP-029）：优先 classpath 内置开源字体，失败回退系统字体；
     * 两者皆无时抛异常并提示安装字体（避免生成乱码 PDF）。
     */
    private PDType0Font loadChineseFont(PDDocument doc) {
        // GAP-029：内置开源中文字体（MiSans-Regular.otf，随包分发），
        // 无系统字体环境（精简 Linux 容器等）也可正常导出中文 PDF
        String bundled = "fonts/MiSans-Regular.otf";
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(bundled)) {
            if (in != null) {
                return PDType0Font.load(doc, in);
            }
            LOGGER.warn("内置字体资源缺失[{}]，回退系统字体", bundled);
        } catch (Exception e) {
            LOGGER.warn("内置字体加载失败[{}]: {}", bundled, e.getMessage());
        }
        // 回退：系统字体扫描（Windows + Linux CI 常见路径）
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    if (path.endsWith(".ttc")) {
                        return PDType0Font.load(doc, new java.io.FileInputStream(f), true);
                    }
                    return PDType0Font.load(doc, f);
                } catch (Exception e) {
                    LOGGER.warn("加载字体失败[{}]: {}", path, e.getMessage());
                }
            }
        }
        LOGGER.warn("未找到系统中文字体，PDF中文可能显示异常");
        throw new IllegalStateException("未找到可用的中文字体（内置字体缺失且系统无中文字体，请安装中文字体后重试），无法生成中文PDF");
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 4.4 整改：为 Word 报告添加页眉（自定义页眉文本，每页显示） */
    private void addDocHeader(XWPFDocument doc, String headerText) {
        try {
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph p = header.createParagraph();
            p.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun run = p.createRun();
            run.setText(headerText);
            run.setFontSize(9);
            run.setColor("999999");
        } catch (Exception e) {
            LOGGER.warn("设置 Word 页眉失败（忽略）: {}", e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }

    /** 统计数值转字符串（null 归零） */
    private String toStatString(Object v) {
        return v == null ? "0" : String.valueOf(v);
    }

    private String mapStatusText(Map<String, Object> row) {
        String status = String.valueOf(row.getOrDefault("status", ""));
        String consistency = String.valueOf(row.getOrDefault("consistencyStatus", "null"));
        if ("missing".equals(status)) return "需求缺失";
        switch (consistency) {
            case "consistent": return "完全一致";
            case "general_inconsistent": return "一般不一致";
            case "serious_inconsistent": return "严重不一致";
            default: return "未覆盖";
        }
    }

    /** 反向追溯矩阵状态：covered 按一致性状态映射，extra 为超范围实现 */
    private String mapReverseStatusText(Map<String, Object> row) {
        if ("extra".equals(String.valueOf(row.get("status")))) {
            return "超范围实现";
        }
        switch (String.valueOf(row.get("consistencyStatus"))) {
            case "consistent": return "完全一致";
            case "general_inconsistent": return "一般不一致";
            case "serious_inconsistent": return "严重不一致";
            default: return "已覆盖";
        }
    }

    private String mapLevelText(Map<String, Object> row) {
        String level = String.valueOf(row.getOrDefault("defectLevel", "null"));
        switch (level) {
            case "serious": return "严重";
            case "general": return "一般";
            default: return "无";
        }
    }
}
