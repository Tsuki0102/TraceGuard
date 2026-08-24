package com.traceguard.util;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentParserUtil 单元测试：docx解析（段落+表格）、需求切分
 */
@DisplayName("文档解析工具单元测试")
class DocumentParserUtilTest {

    private final DocumentParserUtil parser = new DocumentParserUtil();

    @Test
    @DisplayName("docx正文段落被完整提取")
    void docxParagraphContentExtracted() throws Exception {
        Path file = Files.createTempFile("traceguard-para", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText("1. 系统应支持用户注册功能");
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                doc.write(fos);
            }
        }
        String content = parser.parseDocument(file.toString());
        assertThat(content).contains("系统应支持用户注册功能");
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("docx表格内容被完整提取（getParagraphs不含表格，需按body元素遍历）")
    void docxTableContentExtracted() throws Exception {
        Path file = Files.createTempFile("traceguard-table", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText("需求规格表");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("需求编号");
            table.getRow(0).getCell(1).setText("需求描述");
            table.getRow(1).getCell(0).setText("REQ-001");
            table.getRow(1).getCell(1).setText("系统应支持用户登录");
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                doc.write(fos);
            }
        }
        String content = parser.parseDocument(file.toString());
        assertThat(content).contains("需求规格表");
        assertThat(content).contains("REQ-001");
        assertThat(content).contains("系统应支持用户登录");
        // 单元格间以分隔符拼接，保持行内可读性
        assertThat(content).contains("|");
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("短需求条目在编号起点处正确切分（无长度门槛）")
    void shortRequirementsSplitAtNumberedStart() {
        String content = "1. 登录\n2. 登出\n";
        List<String> requirements = parser.splitRequirements(content);
        assertThat(requirements).hasSize(2);
        assertThat(requirements.get(0)).isEqualTo("1. 登录");
        assertThat(requirements.get(1)).isEqualTo("2. 登出");
    }

    @Test
    @DisplayName("标题词后跟正文的行被识别为需求起点（约束：xxx）")
    void headingLineWithBodyTextIsRequirementStart() {
        String content = "1. 系统应支持用户注册并提供邮箱验证\n约束：密码长度不少于8位\n";
        List<String> requirements = parser.splitRequirements(content);
        assertThat(requirements).hasSize(2);
        assertThat(requirements.get(1)).startsWith("约束：密码长度不少于8位");
    }

    // ==================== FR-REQ-001 规则2：内容清洗层（2.1 整改项） ====================

    @Test
    @DisplayName("2.1 清洗层：剔除页码/页眉/页脚/分隔线等无业务含义行")
    void cleanContentRemovesPageNumberAndSeparators() {
        String content = "第 1 页\n" +
                "TraceGuard 需求文档\n" +
                "------------------\n" +
                "1. 系统应支持用户登录\n" +
                "- 2 -\n" +
                "第 2 页 共 5 页\n";
        String cleaned = parser.cleanContent(content);
        assertThat(cleaned).doesNotContain("第 1 页");
        assertThat(cleaned).doesNotContain("第 2 页 共 5 页");
        assertThat(cleaned).doesNotContain("- 2 -");
        assertThat(cleaned).doesNotContain("------------------");
        assertThat(cleaned).contains("1. 系统应支持用户登录");
    }

    @Test
    @DisplayName("2.1 清洗层：剔除 markdown 表格分隔线与 HTML 标签残留")
    void cleanContentRemovesTableSeparatorAndHtml() {
        String content = "# 需求说明\n" +
                "| 需求编号 | 需求描述 |\n" +
                "| --- | --- |\n" +
                "| REQ-001 | 系统应支持 <b>用户</b> 登录 |\n" +
                "<div style=\"display:none\">隐藏内容</div>\n";
        String cleaned = parser.cleanContent(content);
        assertThat(cleaned).doesNotContain("| --- | --- |");
        assertThat(cleaned).doesNotContain("<b>");
        assertThat(cleaned).doesNotContain("<div");
        assertThat(cleaned).doesNotContain("隐藏内容");
        // 标题标记与链接文本被保留（# 前缀去除、[text](url) 保留可见文本）
        assertThat(cleaned).contains("需求说明");
        assertThat(cleaned).contains("系统应支持 用户 登录");
    }

    @Test
    @DisplayName("2.1 清洗层：剔除目录条目（标题+点线+页码）")
    void cleanContentRemovesTocEntries() {
        String content = "目录\n" +
                "1.1 功能需求.......... 3\n" +
                "1.2 非功能需求 …… 5\n" +
                "1. 系统应支持用户登录\n";
        String cleaned = parser.cleanContent(content);
        assertThat(cleaned).doesNotContain("功能需求..........");
        assertThat(cleaned).doesNotContain("非功能需求");
        assertThat(cleaned).contains("1. 系统应支持用户登录");
    }

    @Test
    @DisplayName("2.1 清洗层：重复出现的页眉/页脚短文本被剔除，正文内容不受影响")
    void cleanContentRemovesRepeatedHeaderFooter() {
        String content = "产品需求规格说明书\n" +
                "1. 系统应支持用户登录\n" +
                "产品需求规格说明书\n" +
                "2. 系统应支持数据导出\n" +
                "产品需求规格说明书\n";
        String cleaned = parser.cleanContent(content);
        assertThat(cleaned).doesNotContain("产品需求规格说明书");
        assertThat(cleaned).contains("1. 系统应支持用户登录");
        assertThat(cleaned).contains("2. 系统应支持数据导出");
    }

    @Test
    @DisplayName("2.1 清洗层：重复出现的短需求不被误判为页眉")
    void cleanContentKeepsShortRepeatedRequirement() {
        String content = "1. 登录\n2. 登录\n3. 登录\n";
        List<String> requirements = parser.splitRequirements(content);
        // 编号起点行不被页眉逻辑剔除
        assertThat(requirements).hasSize(3);
        assertThat(requirements.get(0)).isEqualTo("1. 登录");
    }

    @Test
    @DisplayName("2.1 docx 表格：表头行被跳过，数据行保留")
    void docxTableHeaderRowSkipped() throws Exception {
        Path file = Files.createTempFile("traceguard-header", ".docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("需求编号");
            table.getRow(0).getCell(1).setText("需求描述");
            table.getRow(1).getCell(0).setText("REQ-001");
            table.getRow(1).getCell(1).setText("系统应支持用户登录");
            table.getRow(2).getCell(0).setText("REQ-002");
            table.getRow(2).getCell(1).setText("系统应支持数据导出");
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                doc.write(fos);
            }
        }
        String content = parser.parseDocument(file.toString());
        // 表头（需求编号/需求描述）被剔除
        assertThat(content).doesNotContain("需求编号");
        assertThat(content).doesNotContain("需求描述");
        // 数据行保留
        assertThat(content).contains("REQ-001");
        assertThat(content).contains("系统应支持用户登录");
        assertThat(content).contains("REQ-002");
        assertThat(content).contains("系统应支持数据导出");
        Files.deleteIfExists(file);
    }
}
