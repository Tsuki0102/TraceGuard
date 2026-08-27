package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * GAP-010：Word 报告排版统一工具
 * 集中管理段落/标题样式（行间距、缩进、字体、颜色），所有章节渲染器复用
 *
 * 设计要点：
 * - 行距统一 1.5 倍（避免内容拥挤）
 * - 中文字体 SimHei（标题）/ SimSun（正文），Word 自带跨平台
 * - 章节小标题深蓝（#1F4E79）突出视觉层级
 * - 正文段落首行缩进 420 twip（约 21pt）模拟传统中文报告排版
 */
public final class ReportWordStyles {

    private ReportWordStyles() {}

    /** 章节小标题：粗体、SimHei、深蓝、段前/段后间距 */
    public static XWPFParagraph addHeading(XWPFDocument doc, String text, int fontSize) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(360);
        p.setSpacingAfter(160);
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(fontSize);
        run.setFontFamily("SimHei");
        run.setColor("1F4E79");
        return p;
    }

    /** 正文段落：1.5 倍行距、SimSun、首行缩进 */
    public static XWPFParagraph addParagraph(XWPFDocument doc, String text) {
        return addParagraph(doc, text, 11, false);
    }

    public static XWPFParagraph addParagraph(XWPFDocument doc, String text, int fontSize, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        p.setIndentationFirstLine(420);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        if (bold) run.setBold(true);
        run.setFontFamily("SimSun");
        return p;
    }

    /** 居中段落（用于封面副标题、关键标识） */
    public static XWPFParagraph addCenteredParagraph(XWPFDocument doc, String text, int fontSize, boolean bold, String color) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        if (bold) run.setBold(true);
        if (color != null) run.setColor(color);
        run.setFontFamily("SimHei");
        return p;
    }
}