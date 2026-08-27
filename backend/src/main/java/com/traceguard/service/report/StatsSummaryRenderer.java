package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
        ReportWordStyles.addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        Map<String, Object> stats = ctx.stats;
        ReportWordStyles.addLabelValue(doc, "需求总数：", toStatString(stats.get("totalRequirements")), null);
        ReportWordStyles.addLabelValue(doc, "需求覆盖率：", toStatString(stats.get("coverageRate")) + "%",
                ReportWordStyles.COLOR_HEADING);
        ReportWordStyles.addLabelValue(doc, "缺陷总数：", toStatString(stats.get("totalDefects"))
                + "（严重：" + toStatString(stats.get("seriousDefects"))
                + "，一般：" + toStatString(stats.get("generalDefects")) + "）", null);
        ReportWordStyles.addLabelValue(doc, "代码基础缺陷数：", toStatString(stats.get("codeDefects")), null);
        ReportWordStyles.addSpacer(doc);
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        Map<String, Object> stats = ctx.stats;
        cursor.sectionTitle(ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        cursor.labelValue("需求总数：", toStatString(stats.get("totalRequirements")));
        cursor.labelValue("需求覆盖率：", toStatString(stats.get("coverageRate")) + "%");
        cursor.labelValue("缺陷总数：", toStatString(stats.get("totalDefects"))
                + "（严重：" + toStatString(stats.get("seriousDefects"))
                + "，一般：" + toStatString(stats.get("generalDefects")) + "）");
        cursor.labelValue("代码基础缺陷数：", toStatString(stats.get("codeDefects")));
        cursor.spacer();
    }

    private String toStatString(Object v) {
        return v == null ? "0" : String.valueOf(v);
    }
}
