package com.traceguard.service;

import com.traceguard.common.ReportTemplate;
import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Project;
import com.traceguard.entity.ReportTemplateConfig;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.service.report.PdfCursorAdapter;
import com.traceguard.service.report.ReportContext;
import com.traceguard.service.report.ReportSectionRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExportService 单元测试（C6/C7/C8）
 * 覆盖：缺陷Excel代码片段列、反向追溯矩阵Excel、统计报表Excel、多项目对比Excel、
 * Word/PDF 报告模板门控（FULL/DEFECT_ONLY/BRIEF）
 */
@DisplayName("导出服务单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ResultService resultService;

    @InjectMocks
    private ExportService exportService;

    /** GAP-010：注入6个测试渲染器，使注册表化渲染正常工作并产生可验证的输出 */
    @BeforeEach
    void injectMockRenderers() throws Exception {
        String[][] rendererDefs = {
                {"project-overview", "项目概况"},
                {"stats-summary", "需求覆盖率与缺陷统计"},
                {"defect-type-distribution", "缺陷类型分布"},
                {"defect-detail", "需求-代码不一致缺陷明细"},
                {"code-quality", "代码质量分析"},
                {"traceability-matrix", "追溯矩阵"}
        };
        List<ReportSectionRenderer> renderers = new ArrayList<>();
        for (String[] def : rendererDefs) {
            String key = def[0];
            String title = def[1];
            ReportSectionRenderer r = mock(ReportSectionRenderer.class);
            when(r.key()).thenReturn(key);
            when(r.defaultTitle()).thenReturn(title);
            // Word 渲染：写入章节标题与 key 标识（可被全文断言捕获）
            doAnswer(inv -> {
                XWPFDocument doc = inv.getArgument(1);
                String sTitle = inv.getArgument(2);
                int sNo = inv.getArgument(3);
                String heading = ReportSectionRenderer.chineseNumber(sNo) + "、" + sTitle;
                doc.createParagraph().createRun().setText(heading);
                doc.createParagraph().createRun().setText("[" + key + "]");
                // 追溯矩阵渲染器模拟写入实际数据行
                if ("traceability-matrix".equals(key)) {
                    ReportContext ctx = inv.getArgument(0);
                    if (ctx.traceabilityMatrix != null && !ctx.traceabilityMatrix.isEmpty()) {
                        Map<String, Object> row = ctx.traceabilityMatrix.get(0);
                        doc.createParagraph().createRun().setText("REQ行:" + row.getOrDefault("requirementId", ""));
                        doc.createParagraph().createRun().setText("文件:" + row.getOrDefault("filePath", ""));
                    }
                }
                // 缺陷明细渲染器模拟写入代码片段
                if ("defect-detail".equals(key)) {
                    ReportContext ctx = inv.getArgument(0);
                    if (ctx.defects != null && !ctx.defects.isEmpty()) {
                        for (Defect d : ctx.defects) {
                            doc.createParagraph().createRun().setText("代码片段:" + (d.getCodeSnippet() != null ? d.getCodeSnippet() : ""));
                        }
                    }
                }
                return null;
            }).when(r).renderWord(any(ReportContext.class), any(XWPFDocument.class), anyString(), anyInt());
            // PDF 渲染：写入章节标题
            doAnswer(inv -> {
                PdfCursorAdapter cursor = inv.getArgument(1);
                String sTitle = inv.getArgument(2);
                int sNo = inv.getArgument(3);
                String heading = ReportSectionRenderer.chineseNumber(sNo) + "、" + sTitle;
                cursor.line(14, true, heading);
                cursor.line(12, false, "[" + key + "]");
                return null;
            }).when(r).renderPdf(any(ReportContext.class), any(PdfCursorAdapter.class), anyString(), anyInt());
            renderers.add(r);
        }
        // 通过反射注入 sectionRenderers
        Field f = ExportService.class.getDeclaredField("sectionRenderers");
        f.setAccessible(true);
        f.set(exportService, renderers);
        // 初始化注册表
        exportService.initRendererRegistry();
    }

    // ==================== 辅助构造 ====================

    private Defect buildDefect() {
        Defect d = new Defect();
        d.setDefectId("DEF-001");
        d.setDefectType("缺失实现");
        d.setDefectLevel("serious");
        d.setRequirementText("系统应支持用户登录并校验密码");
        d.setCodeSnippet("public void login(String user, String pwd) { }");
        d.setDefectReason("需求要求登录校验，代码未见实现");
        d.setRepairSuggestion("补充登录密码校验逻辑");
        return d;
    }

    private Project buildProject() {
        Project p = new Project();
        p.setId(1L);
        p.setProjectName("演示项目");
        p.setIndustryType("金融");
        p.setTechStack("Java 8 / SpringBoot");
        return p;
    }

    private Map<String, Object> buildStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequirements", 5);
        stats.put("coverageRate", 80.0);
        stats.put("totalDefects", 2);
        stats.put("seriousDefects", 1);
        stats.put("generalDefects", 1);
        stats.put("codeDefects", 1);
        stats.put("highCodeDefects", 1);
        stats.put("mediumCodeDefects", 0);
        stats.put("codeQualityScore", 85);
        Map<String, Object> typeDist = new HashMap<>();
        typeDist.put("缺失实现", 1);
        typeDist.put("实现偏离", 1);
        stats.put("defectTypeDistribution", typeDist);
        return stats;
    }

    private Map<String, Object> buildForwardMatrixRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("requirementId", "REQ-001");
        row.put("requirementText", "系统应支持用户登录");
        row.put("status", "covered");
        row.put("consistencyStatus", "serious_inconsistent");
        row.put("similarity", 0.32);
        row.put("filePath", "src/main/java/UserService.java");
        row.put("startLine", 10);
        row.put("defectLevel", "serious");
        row.put("repairSuggestion", "补充登录实现");
        return row;
    }

    /** 提取 Word 全文文本 */
    private String wordText(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
            return sb.toString();
        }
    }

    /** 提取 PDF 全文文本 */
    private String pdfText(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(bytes))) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ==================== C6：缺陷Excel 补代码片段列 ====================

    @Test
    @DisplayName("缺陷清单Excel含“相关代码片段”列且写入片段内容")
    void defectsExcelContainsCodeSnippetColumn() throws Exception {
        when(resultService.getDefects(1L, null, null))
                .thenReturn(Collections.singletonList(buildDefect()));

        byte[] bytes = exportService.exportDefectsExcel(1L);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = wb.getSheet("缺陷清单");
            assertThat(sheet).isNotNull();
            // GAP-020/011 + 2.6 行号整改：表头顺序 = 缺陷ID/类型/子类型/状态/等级/缺陷行号/需求原文/代码片段/原因/建议
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue())
                    .isEqualTo("相关需求原文");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue())
                    .contains("用户登录");
            assertThat(sheet.getRow(0).getCell(7).getStringCellValue())
                    .isEqualTo("相关代码片段");
            assertThat(sheet.getRow(1).getCell(7).getStringCellValue())
                    .contains("login");
        }
    }

    // ==================== C6：反向追溯矩阵Excel ====================

    @Test
    @DisplayName("反向追溯矩阵Excel状态映射：extra->超范围实现，covered+consistent->完全一致")
    void reverseTraceabilityExcelStatusMapping() throws Exception {
        Map<String, Object> extraRow = new HashMap<>();
        extraRow.put("filePath", "src/ExtraService.java");
        extraRow.put("className", "ExtraService");
        extraRow.put("methodName", "unusedMethod");
        extraRow.put("startLine", 20);
        extraRow.put("status", "extra");
        extraRow.put("similarity", 0.12);

        Map<String, Object> coveredRow = new HashMap<>();
        coveredRow.put("filePath", "src/UserService.java");
        coveredRow.put("className", "UserService");
        coveredRow.put("methodName", "login");
        coveredRow.put("startLine", 10);
        coveredRow.put("status", "covered");
        coveredRow.put("consistencyStatus", "consistent");
        coveredRow.put("requirementId", "REQ-001");
        coveredRow.put("similarity", 0.92);

        when(resultService.getReverseTraceabilityMatrix(1L))
                .thenReturn(Arrays.asList(extraRow, coveredRow));

        byte[] bytes = exportService.exportReverseTraceabilityExcel(1L);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = wb.getSheet("反向追溯矩阵");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(5).getStringCellValue()).isEqualTo("追溯状态");
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("超范围实现");
            assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("完全一致");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("REQ-001");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("20");
        }
    }

    // ==================== C7：统计报表Excel ====================

    @Test
    @DisplayName("统计报表Excel含统计指标与缺陷类型分布两个Sheet")
    void statisticsExcelHasTwoSheets() throws Exception {
        when(resultService.getProjectStatistics(1L)).thenReturn(buildStats());

        byte[] bytes = exportService.exportStatisticsExcel(1L);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet metricSheet = wb.getSheet("统计指标");
            XSSFSheet typeSheet = wb.getSheet("缺陷类型分布");
            assertThat(metricSheet).isNotNull();
            assertThat(typeSheet).isNotNull();

            // 首行指标：需求总数=5
            assertThat(metricSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("需求总数");
            assertThat(metricSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("5");
            // 类型分布应有数据行：表头(0) + 2条类型(1,2)
            assertThat(typeSheet.getLastRowNum()).isEqualTo(2);
            assertThat(typeSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("缺陷类型");
            assertThat(typeSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("1");
        }
    }

    // ==================== C7：多项目对比Excel ====================

    @Test
    @DisplayName("多项目对比Excel按项目逐行输出对比指标")
    void compareExcelWritesProjectRows() throws Exception {
        Map<String, Object> row = buildStats();
        row.put("projectId", 1L);
        row.put("projectName", "演示项目");
        row.put("industryType", "金融");
        when(resultService.compareProjects(Arrays.asList(1L, 2L)))
                .thenReturn(Collections.singletonList(row));

        byte[] bytes = exportService.exportCompareExcel(Arrays.asList(1L, 2L));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = wb.getSheet("多项目对比统计");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 12);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("演示项目");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("金融");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("5");
        }
    }

    // ==================== C6+C8：Word 报告 ====================

    @Test
    @DisplayName("Word完整模板：含追溯矩阵实际数据与代码片段（FR-CHECK-004/C6）")
    void reportWordFullTemplate() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(buildProject());
        when(resultService.getProjectStatistics(1L)).thenReturn(buildStats());
        when(resultService.getDefects(1L, null, null))
                .thenReturn(Collections.singletonList(buildDefect()));
        when(resultService.getCodeDefects(1L, null)).thenReturn(Collections.emptyList());
        when(resultService.getTraceabilityMatrix(1L))
                .thenReturn(Collections.singletonList(buildForwardMatrixRow()));

        String text = wordText(exportService.exportReportWord(1L, ReportTemplate.FULL));
        assertThat(text).contains("追溯矩阵");
        assertThat(text).contains("REQ-001");
        assertThat(text).contains("UserService.java");
        assertThat(text).contains("代码片段");
        assertThat(text).contains("login");
    }

    @Test
    @DisplayName("Word缺陷聚焦模板：含缺陷明细与代码片段，不含追溯矩阵与类型分布")
    void reportWordDefectOnlyTemplate() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(buildProject());
        when(resultService.getProjectStatistics(1L)).thenReturn(buildStats());
        when(resultService.getDefects(1L, null, null))
                .thenReturn(Collections.singletonList(buildDefect()));
        when(resultService.getCodeDefects(1L, null)).thenReturn(Collections.emptyList());

        String text = wordText(exportService.exportReportWord(1L, ReportTemplate.DEFECT_ONLY));
        assertThat(text).contains("缺陷明细");
        assertThat(text).contains("代码片段");
        assertThat(text).doesNotContain("追溯矩阵");
        assertThat(text).doesNotContain("缺陷类型分布");
    }

    @Test
    @DisplayName("Word简要模板：仅概况与统计，不含缺陷明细与追溯矩阵")
    void reportWordBriefTemplate() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(buildProject());
        when(resultService.getProjectStatistics(1L)).thenReturn(buildStats());
        when(resultService.getDefects(1L, null, null)).thenReturn(Collections.emptyList());
        when(resultService.getCodeDefects(1L, null)).thenReturn(Collections.emptyList());

        String text = wordText(exportService.exportReportWord(1L, ReportTemplate.BRIEF));
        assertThat(text).contains("项目概况");
        assertThat(text).contains("需求覆盖率与缺陷统计");
        assertThat(text).doesNotContain("缺陷明细");
        assertThat(text).doesNotContain("代码质量分析");
        assertThat(text).doesNotContain("追溯矩阵");
    }

    // ==================== C6+C8：PDF 报告 ====================

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("PDF完整模板：含缺陷分级统计、代码质量分析与追溯矩阵三块")
    void reportPdfFullTemplate() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(buildProject());
        when(resultService.getProjectStatistics(1L)).thenReturn(buildStats());
        when(resultService.getDefects(1L, null, null))
                .thenReturn(Collections.singletonList(buildDefect()));
        when(resultService.getCodeDefects(1L, null)).thenReturn(Collections.emptyList());
        when(resultService.getTraceabilityMatrix(1L))
                .thenReturn(Collections.singletonList(buildForwardMatrixRow()));

        String text = pdfText(exportService.exportReportPdf(1L, ReportTemplate.FULL));
        assertThat(text).contains("需求覆盖率与缺陷统计");
        assertThat(text).contains("代码质量分析");
        assertThat(text).contains("追溯矩阵");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("PDF简要模板：不含缺陷分级统计/缺陷明细/代码质量/追溯矩阵")
    void reportPdfBriefTemplate() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(buildProject());
        when(resultService.getProjectStatistics(1L)).thenReturn(buildStats());
        when(resultService.getDefects(1L, null, null)).thenReturn(Collections.emptyList());
        when(resultService.getCodeDefects(1L, null)).thenReturn(Collections.emptyList());

        String text = pdfText(exportService.exportReportPdf(1L, ReportTemplate.BRIEF));
        assertThat(text).contains("需求覆盖率与缺陷统计");
        assertThat(text).doesNotContain("缺陷分级统计");
        assertThat(text).doesNotContain("缺陷明细");
        assertThat(text).doesNotContain("代码质量分析");
        assertThat(text).doesNotContain("追溯矩阵");
    }
}
