package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * GAP-010：章节渲染器 - 项目概况
 */
@Component
public class ProjectOverviewRenderer implements ReportSectionRenderer {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String key() {
        return "project-overview";
    }

    @Override
    public String defaultTitle() {
        return "项目概况";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        ReportWordStyles.addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        ReportWordStyles.addLabelValue(doc, "项目名称：", nullToEmpty(ctx.project.getProjectName()), null);
        ReportWordStyles.addLabelValue(doc, "行业类型：", nullToEmpty(ctx.project.getIndustryType()), null);
        ReportWordStyles.addLabelValue(doc, "技术栈：", nullToEmpty(ctx.project.getTechStack()), null);
        ReportWordStyles.addLabelValue(doc, "报告生成时间：", DTF.format(LocalDateTime.now()), null);
        ReportWordStyles.addSpacer(doc);
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        cursor.sectionTitle(ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        cursor.labelValue("项目名称：", nullToEmpty(ctx.project.getProjectName()));
        cursor.labelValue("行业类型：", nullToEmpty(ctx.project.getIndustryType()));
        cursor.labelValue("技术栈：", nullToEmpty(ctx.project.getTechStack()));
        cursor.labelValue("报告生成时间：", DTF.format(LocalDateTime.now()));
        cursor.spacer();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
