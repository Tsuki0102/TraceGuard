package com.traceguard.service.report;

import com.traceguard.entity.CodeDefect;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
        ReportWordStyles.addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        ReportWordStyles.addParagraph(doc, "静态分析共发现 " + ctx.codeDefects.size() + " 个代码基础缺陷。");
        int cidx = 1;
        for (CodeDefect d : ctx.codeDefects) {
            ReportWordStyles.addListItem(doc, (cidx++) + ". [" + nullToEmpty(d.getSeverity()) + "] "
                    + nullToEmpty(d.getDefectType()) + " - "
                    + nullToEmpty(d.getFilePath())
                    + (d.getLineNumber() != null ? " 第" + d.getLineNumber() + "行" : ""));
            ReportWordStyles.addItemDetail(doc, truncate(nullToEmpty(d.getDescription()), 150));
        }
        if (ctx.codeDefects.isEmpty()) {
            ReportWordStyles.addParagraph(doc, "未检测到代码基础缺陷。");
        }
        ReportWordStyles.addSpacer(doc);
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        cursor.sectionTitle(ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        cursor.line(11, false, "静态分析共发现 " + ctx.codeDefects.size() + " 个代码基础缺陷。");
        int cdidx = 1;
        for (CodeDefect d : ctx.codeDefects) {
            cursor.line(11, true, (cdidx++) + ". [" + nullToEmpty(d.getSeverity()) + "] "
                    + nullToEmpty(d.getDefectType()) + " " + nullToEmpty(d.getFilePath())
                    + (d.getLineNumber() != null ? " 第" + d.getLineNumber() + "行" : ""));
            cursor.itemDetail(truncate(nullToEmpty(d.getDescription()), 100));
        }
        if (ctx.codeDefects.isEmpty()) {
            cursor.line(11, false, "未检测到代码基础缺陷。");
        }
        cursor.spacer();
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
