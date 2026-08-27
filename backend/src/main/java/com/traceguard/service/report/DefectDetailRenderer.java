package com.traceguard.service.report;

import com.traceguard.common.DefectStatus;
import com.traceguard.entity.Defect;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

/**
 * GAP-010：章节渲染器 - 需求-代码不一致缺陷明细（含 GAP-011 状态标注）
 */
@Component
public class DefectDetailRenderer implements ReportSectionRenderer {

    private static final int MAX_TEXT_LEN = 200;
    private static final int MAX_SNIPPET_LEN = 300;
    /** 代码片段最多展示行数，超出截断提示，避免报告被大段代码淹没 */
    private static final int MAX_SNIPPET_LINES = 40;

    @Override
    public String key() {
        return "defect-detail";
    }

    @Override
    public String defaultTitle() {
        return "需求-代码不一致缺陷明细";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        int idx = 1;
        for (Defect d : ctx.defects) {
            addParagraph(doc, (idx++) + ". [" + ("serious".equals(d.getDefectLevel()) ? "严重" : "一般") + "] "
                    + formatDefectType(d) + "（" + nullToEmpty(d.getDefectId()) + "）");
            if (d.getRequirementText() != null) {
                addParagraph(doc, "需求原文：" + truncate(d.getRequirementText(), MAX_TEXT_LEN));
            }
            if (d.getCodeSnippet() != null && !d.getCodeSnippet().isEmpty()) {
                addParagraph(doc, "代码片段：");
                addCodeBlock(doc, d.getCodeSnippet());
            }
            addParagraph(doc, "缺陷原因：" + truncate(nullToEmpty(d.getDefectReason()), MAX_SNIPPET_LEN));
            addParagraph(doc, "修复建议：" + truncate(nullToEmpty(d.getRepairSuggestion()), MAX_SNIPPET_LEN));
        }
        if (ctx.defects.isEmpty()) {
            addParagraph(doc, "未检测到需求-代码不一致缺陷。");
        }
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        cursor.line(14, true, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        int idx = 1;
        for (Defect d : ctx.defects) {
            cursor.line(12, true, (idx++) + ". ["
                    + ("serious".equals(d.getDefectLevel()) ? "严重" : "一般") + "] "
                    + formatDefectType(d));
            cursor.indent(11, "原因：" + truncate(nullToEmpty(d.getDefectReason()), 90));
            if (d.getCodeSnippet() != null && !d.getCodeSnippet().isEmpty()) {
                cursor.indent(11, "代码片段：");
                renderCodeBlock(cursor, d.getCodeSnippet());
            }
            cursor.indent(11, "建议：" + truncate(nullToEmpty(d.getRepairSuggestion()), 90));
        }
        if (ctx.defects.isEmpty()) {
            cursor.line(12, false, "未检测到需求-代码不一致缺陷。");
        }
        cursor.gap();
    }

    /** Word 代码块：保留原始换行逐行输出，等宽字体 */
    private void addCodeBlock(XWPFDocument doc, String code) {
        String[] lines = code.replaceAll("\r\n", "\n").split("\n", -1);
        int shown = 0;
        for (String line : lines) {
            if (shown >= MAX_SNIPPET_LINES) {
                ReportWordStyles.addCodeLine(doc, "...（代码片段过长已截断）");
                break;
            }
            ReportWordStyles.addCodeLine(doc, line);
            shown++;
        }
    }

    /** PDF 代码块：保留原始换行逐行输出（自动换行由游标适配器处理） */
    private void renderCodeBlock(PdfCursorAdapter cursor, String code) throws Exception {
        String[] lines = code.replaceAll("\r\n", "\n").split("\n", -1);
        int shown = 0;
        for (String line : lines) {
            if (shown >= MAX_SNIPPET_LINES) {
                cursor.subIndent(9, "...（代码片段过长已截断）");
                break;
            }
            cursor.subIndent(9, line);
            shown++;
        }
    }

    /** GAP-020：缺陷类型展示格式 = 主类型（子类型括注）；GAP-011：加状态前缀 */
    private String formatDefectType(Defect d) {
        String prefix = nullToEmpty(d.getStatus());
        if (!prefix.isEmpty() && !"pending".equals(prefix)) {
            prefix = "[" + DefectStatus.labelOf(prefix) + "] ";
        } else {
            prefix = "";
        }
        String main = nullToEmpty(d.getDefectType());
        String sub = nullToEmpty(d.getSubType());
        if (!sub.isEmpty() && !sub.equals(main)) {
            return prefix + main + "（" + sub + "）";
        }
        return prefix + main;
    }

    private void addHeading(XWPFDocument doc, String text, int fontSize) {
        ReportWordStyles.addHeading(doc, text, fontSize);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        ReportWordStyles.addParagraph(doc, text);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        // 保留换行（\n），仅把制表符转空格，避免多行文本被压成一行
        String cleaned = s.replaceAll("[\\t]+", " ").replaceAll("[\\r\\n]+", "\n").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }
}
