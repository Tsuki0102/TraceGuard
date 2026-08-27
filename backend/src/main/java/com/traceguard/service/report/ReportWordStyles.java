package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * GAP-010：Word 报告排版统一工具
 * 集中管理全文排版语言（层级标题/正文/条目/代码/空行/封面），所有章节渲染器复用，
 * 保证整份报告风格统一、疏密有致、便于阅读。
 *
 * 排版规范：
 * - 字距：标题 +8（1/20pt，约 0.4pt），正文不额外拉开，避免突兀
 * - 行距：正文 1.5 倍，代码单倍，条目 1.3 倍
 * - 段距：章节标题段前 400 / 段后 240；正文段后 100；条目段前 120 段后 40
 * - 字体：标题 SimHei，正文 SimSun，代码 Consolas（中文兜底 SimSun）
 * - 颜色：章节标题 #1F4E79（深蓝）、条目标题 #2F5597、正文 #000000、弱化 #595959
 */
public final class ReportWordStyles {

    private ReportWordStyles() {}

    public static final String COLOR_HEADING = "1F4E79";
    public static final String COLOR_ITEM = "2F5597";
    public static final String COLOR_GRAY = "595959";
    public static final String COLOR_LIGHT = "808080";

    public static final String FONT_HEADING = "SimHei";
    public static final String FONT_BODY = "SimSun";
    public static final String FONT_CODE = "Consolas";

    /** 章节小标题：粗体 SimHei 深蓝、段前/段后、字距微增 */
    public static XWPFParagraph addHeading(XWPFDocument doc, String text, int fontSize) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(400);
        p.setSpacingAfter(240);
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        p.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = p.createRun();
        run.setText(text);
        setExplicitBold(run, true);
        run.setFontSize(fontSize);
        run.setFontFamily(FONT_HEADING);
        run.setColor(COLOR_HEADING);
        run.setCharacterSpacing(8);
        return p;
    }

    /** 正文段落：1.5 倍行距、SimSun、首行缩进、段后间距 */
    public static XWPFParagraph addParagraph(XWPFDocument doc, String text) {
        return addParagraph(doc, text, 11, false);
    }

    public static XWPFParagraph addParagraph(XWPFDocument doc, String text, int fontSize, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        p.setSpacingAfter(100);
        p.setIndentationFirstLine(420);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        setExplicitBold(run, bold);
        run.setFontFamily(FONT_BODY);
        return p;
    }

    /** 标签-值段落：标签加粗 + 值高亮（关键统计数字醒目且风格统一） */
    public static XWPFParagraph addLabelValue(XWPFDocument doc, String label, String value, String valueColor) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        p.setSpacingAfter(100);
        p.setIndentationFirstLine(420);
        XWPFRun labelRun = p.createRun();
        labelRun.setText(label);
        labelRun.setFontSize(11);
        setExplicitBold(labelRun, true);
        labelRun.setFontFamily(FONT_BODY);
        XWPFRun valueRun = p.createRun();
        valueRun.setText(value);
        valueRun.setFontSize(11);
        setExplicitBold(valueRun, true);
        valueRun.setFontFamily(FONT_BODY);
        if (valueColor != null) valueRun.setColor(valueColor);
        return p;
    }

    /** 条目标题行（缺陷/缺陷列表序号项）：加粗中蓝、段前/段后紧凑、1.3 倍行距 */
    public static XWPFParagraph addListItem(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(160);
        p.setSpacingAfter(60);
        p.setSpacingBetween(1.3, LineSpacingRule.AUTO);
        XWPFRun run = p.createRun();
        run.setText(text);
        setExplicitBold(run, true);
        run.setFontSize(11);
        run.setFontFamily(FONT_HEADING);
        run.setColor(COLOR_ITEM);
        // 显式关闭下划线
        run.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE);
        return p;
    }

    /** 条目下标签行：12pt 加粗（比正文大一号）、深灰、左侧缩进 */
    public static XWPFParagraph addItemLabel(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.4, LineSpacingRule.AUTO);
        p.setSpacingAfter(60);
        p.setIndentationLeft(420);
        p.setIndentationFirstLine(0);
        XWPFRun run = p.createRun();
        run.setText(text);
        setExplicitBold(run, true);
        run.setFontSize(12);
        run.setFontFamily(FONT_BODY);
        run.setColor(COLOR_GRAY);
        return p;
    }

    /** 条目下标签-值同行：标签 12pt 加粗 + 值 10.5pt 普通 */
    public static XWPFParagraph addItemLabelValue(XWPFDocument doc, String label, String value) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.4, LineSpacingRule.AUTO);
        p.setSpacingAfter(80);
        p.setIndentationLeft(420);
        p.setIndentationFirstLine(0);
        XWPFRun lr = p.createRun();
        lr.setText(label);
        setExplicitBold(lr, true);
        lr.setFontSize(12);
        lr.setFontFamily(FONT_BODY);
        lr.setColor(COLOR_GRAY);
        if (value != null && !value.isEmpty()) {
            XWPFRun vr = p.createRun();
            vr.setText(value);
            setExplicitBold(vr, false);
            vr.setFontSize(10.5);
            vr.setFontFamily(FONT_BODY);
        }
        return p;
    }

    /** 条目下说明行（向后兼容）：10.5pt 宋体（非粗体）、段后小间距、无首行缩进 */
    public static XWPFParagraph addItemDetail(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.4, LineSpacingRule.AUTO);
        p.setSpacingAfter(80);
        p.setIndentationLeft(420);
        p.setIndentationFirstLine(0);
        XWPFRun run = p.createRun();
        run.setText(text);
        setExplicitBold(run, false);
        run.setFontSize(10.5);
        run.setFontFamily(FONT_BODY);
        return p;
    }

    /**
     * 显式设置粗体：先关再开确保覆盖 POI 继承的默认样式。
     * 某些 Word 主题对 XWPFRun 默认加粗，单次 setBold(true) 可能被样式继承覆盖，
     * 连续 setBold(false)+setBold(true) 保证实际写入 w:b 元素。
     */
    private static void setExplicitBold(XWPFRun run, boolean bold) {
        run.setBold(!bold);  // 先反向
        run.setBold(bold);   // 再正向，最终生效
    }

    /** 代码行：等宽字体（Consolas + 中文宋体兜底）、深灰、左侧缩进、单倍行距 */
    public static XWPFParagraph addCodeLine(XWPFDocument doc, String line) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBetween(1.0, LineSpacingRule.AUTO);
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);
        p.setIndentationLeft(560);
        p.setIndentationFirstLine(0);
        XWPFRun run = p.createRun();
        run.setText(line == null ? "" : line);
        setExplicitBold(run, false);
        run.setFontSize(9);
        run.setFontFamily(FONT_CODE);
        run.setFontFamily(FONT_BODY, XWPFRun.FontCharRange.eastAsia);
        run.setColor(COLOR_GRAY);
        return p;
    }

    /** 代码块后空隙：与代码段落衔接的自然留白 */
    public static XWPFParagraph addCodeSpacer(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(0);
        p.setSpacingAfter(80);
        return p;
    }

    /** 空行：报告内章节间留白，控制篇幅又透气 */
    public static XWPFParagraph addSpacer(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(0);
        p.setSpacingAfter(120);
        return p;
    }

    /** 居中段落（封面副标题、关键标识） */
    public static XWPFParagraph addCenteredParagraph(XWPFDocument doc, String text, int fontSize, boolean bold, String color) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        setExplicitBold(run, bold);
        if (color != null) run.setColor(color);
        run.setFontFamily(FONT_HEADING);
        return p;
    }

    /** 封面信息行（项目名称等）：居中、灰、带段前间距 */
    public static XWPFParagraph addCoverInfo(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingBefore(60);
        p.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = p.createRun();
        run.setText(text);
        setExplicitBold(run, false);
        run.setFontSize(12);
        run.setFontFamily(FONT_BODY);
        run.setColor(COLOR_GRAY);
        return p;
    }
}