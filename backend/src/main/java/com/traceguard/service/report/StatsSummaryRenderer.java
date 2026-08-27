package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GAP-010：章节渲染器 - 需求覆盖率与缺陷统计
 */
@Component
public class StatsSummaryRenderer implements ReportSectionRenderer {

    @Override
    public String key() {
        return "stats-summary";
    }

    @Override
    public String defaultTitle() {
        return "需求覆盖率与缺陷统计";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        Map<String, Object> stats = ctx.stats;
        addParagraph(doc, "需求总数：" + toStatString(stats.get("totalRequirements")));
        addParagraph(doc, "需求覆盖率：" + toStatString(stats.get("coverageRate")) + "%");
        addParagraph(doc, "缺陷总数：" + toStatString(stats.get("totalDefects"))
                + "（严重：" + toStatString(stats.get("seriousDefects"))
                + "，一般：" + toStatString(stats.get("generalDefects")) + "）");
        addParagraph(doc, "代码基础缺陷数：" + toStatString(stats.get("codeDefects")));
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        Map<String, Object> stats = ctx.stats;
        cursor.line(14, true, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        cursor.line(12, false, "需求总数：" + toStatString(stats.get("totalRequirements")));
        cursor.line(12, false, "需求覆盖率：" + toStatString(stats.get("coverageRate")) + "%");
        cursor.line(12, false, "缺陷总数：" + toStatString(stats.get("totalDefects"))
                + "（严重：" + toStatString(stats.get("seriousDefects"))
                + "，一般：" + toStatString(stats.get("generalDefects")) + "）");
        cursor.line(12, false, "代码基础缺陷数：" + toStatString(stats.get("codeDefects")));
        cursor.gap();
    }

    private void addHeading(XWPFDocument doc, String text, int fontSize) {
        ReportWordStyles.addHeading(doc, text, fontSize);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        ReportWordStyles.addParagraph(doc, text);
    }

    private String toStatString(Object v) {
        return v == null ? "0" : String.valueOf(v);
    }
}
