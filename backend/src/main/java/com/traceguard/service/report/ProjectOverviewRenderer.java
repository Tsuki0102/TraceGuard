package com.traceguard.service.report;

import com.traceguard.entity.Project;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
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
        addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        addParagraph(doc, "项目名称：" + nullToEmpty(ctx.project.getProjectName()));
        addParagraph(doc, "行业类型：" + nullToEmpty(ctx.project.getIndustryType()));
        addParagraph(doc, "技术栈：" + nullToEmpty(ctx.project.getTechStack()));
        addParagraph(doc, "报告生成时间：" + DTF.format(LocalDateTime.now()));
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        cursor.line(14, true, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        cursor.line(12, false, "项目名称：" + nullToEmpty(ctx.project.getProjectName()));
        cursor.line(12, false, "行业类型：" + nullToEmpty(ctx.project.getIndustryType()));
        cursor.line(12, false, "技术栈：" + nullToEmpty(ctx.project.getTechStack()));
        cursor.line(12, false, "报告生成时间：" + DTF.format(LocalDateTime.now()));
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
}
