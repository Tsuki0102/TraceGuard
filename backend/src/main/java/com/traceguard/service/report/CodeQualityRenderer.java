package com.traceguard.service.report;

import com.traceguard.entity.CodeDefect;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

/**
 * GAP-010：章节渲染器 - 代码质量分析
 */
@Component
public class CodeQualityRenderer implements ReportSectionRenderer {

    @Override
    public String key() {
        return "code-quality";
    }

    @Override
    public String defaultTitle() {
        return "代码质量分析";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        addParagraph(doc, "静态分析共发现 " + ctx.codeDefects.size() + " 个代码基础缺陷。");
        int cidx = 1;
        for (CodeDefect d : ctx.codeDefects) {
            addParagraph(doc, (cidx++) + ". [" + nullToEmpty(d.getSeverity()) + "] "
                    + nullToEmpty(d.getDefectType()) + " - "
                    + nullToEmpty(d.getFilePath())
                    + (d.getLineNumber() != null ? " 第" + d.getLineNumber() + "行" : "")
                    + "：" + truncate(nullToEmpty(d.getDescription()), 150));
        }
        if (ctx.codeDefects.isEmpty()) {
            addParagraph(doc, "未检测到代码基础缺陷。");
        }
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        cursor.line(14, true, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        cursor.line(12, false, "静态分析共发现 " + ctx.codeDefects.size() + " 个代码基础缺陷。");
        int cdidx = 1;
        for (CodeDefect d : ctx.codeDefects) {
            cursor.indent(11, (cdidx++) + ". [" + nullToEmpty(d.getSeverity()) + "] "
                    + nullToEmpty(d.getDefectType()) + " " + nullToEmpty(d.getFilePath())
                    + (d.getLineNumber() != null ? " 第" + d.getLineNumber() + "行" : "")
                    + "：" + truncate(nullToEmpty(d.getDescription()), 60));
        }
        if (ctx.codeDefects.isEmpty()) {
            cursor.line(12, false, "未检测到代码基础缺陷。");
        }
        cursor.gap();
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
        String cleaned = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }
}
