package com.traceguard.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * GAP-010：PDF 输出游标适配器
 * 统一处理自动换行 + 自动分页 + 加粗模拟，供各章节渲染器复用
 *
 * 排版规范（与 Word 端风格对齐）：
 * - 页边距：上下 60pt / 左右 50pt
 * - 缩进阶梯：章节标题 0 / 正文 16 / 代码 32
 * - 行距 = 字号 + 6
 * - 章节标题：字号 14 加粗 + 段后空隙；正文 11；代码 9
 * - 加粗：PDFBox 无内置粗体，用 FILL_THEN_STROKE 描边模拟，避免整文发胖
 */
public class PdfCursorAdapter {
    private static final float A4_WIDTH = PDRectangle.A4.getWidth();
    private static final float A4_HEIGHT = PDRectangle.A4.getHeight();

    private static final float LEFT_MARGIN = 50f;
    private static final float RIGHT_MARGIN = 50f;
    private static final float TOP_MARGIN = 60f;
    private static final float BOTTOM_MARGIN = 60f;

    private static final float MAX_Y = A4_HEIGHT - TOP_MARGIN;
    private static final float MIN_Y = BOTTOM_MARGIN;

    private static final float INDENT_BODY = 16f;
    private static final float INDENT_CODE = 32f;

    private static final float BOLD_STROKE_WIDTH = 0.45f;

    private final PDDocument doc;
    private final PDType0Font font;
    private PDPageContentStream cs;
    private float y;

    public PdfCursorAdapter(PDDocument doc, PDType0Font font) throws Exception {
        this.doc = doc;
        this.font = font;
        newPage();
    }

    /** 章节标题：加粗 + 段后空隙 */
    public void sectionTitle(String text) throws Exception {
        line(14, true, safe(text));
        y -= 10f;
    }

    /** 写一行正文（自动按宽度换行） */
    public void line(int fontSize, boolean bold, String text) throws Exception {
        writeWrapped(LEFT_MARGIN, fontSize, bold, safe(text), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 写一行缩进正文（自动按宽度换行） */
    public void indent(int fontSize, String text) throws Exception {
        writeWrapped(LEFT_MARGIN + INDENT_BODY, fontSize, false, safe(text), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 标签-值行：整体加粗（关键统计数据醒目，风格同 Word 的 labelValue） */
    public void labelValue(String label, String value) throws Exception {
        writeWrapped(LEFT_MARGIN + INDENT_BODY, 11, true, safe(label) + " " + safe(value), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 条目说明行（缺陷原因/建议等） */
    public void itemDetail(String text) throws Exception {
        writeWrapped(LEFT_MARGIN + INDENT_BODY, 10.5f, false, safe(text), A4_WIDTH - RIGHT_MARGIN);
        y -= 2f;
    }

    /** 代码行（缩进 + 小号） */
    public void codeLine(String line) throws Exception {
        writeWrapped(LEFT_MARGIN + INDENT_CODE, 9f, false, safeCode(line), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 章节间空隙 */
    public void gap() {
        y -= 8f;
    }

    /** 空行留白 */
    public void spacer() {
        y -= 12f;
    }

    /** 居中绘制图片（封面品牌 Logo），绘制后游标下移留白 */
    public void drawImageCentered(PDImageXObject image, float displayWidth) throws Exception {
        if (image == null || displayWidth <= 0) {
            return;
        }
        ensureSpace();
        float scale = displayWidth / image.getWidth();
        float h = image.getHeight() * scale;
        float x = (A4_WIDTH - displayWidth) / 2f;
        cs.drawImage(image, x, y - h, displayWidth, h);
        y -= (h + 16f);
    }

    /** 强制分页 */
    public void newPage() throws Exception {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        y = MAX_Y;
    }

    public void close() throws Exception {
        if (cs != null) {
            cs.close();
            cs = null;
        }
    }

    /** 按可用宽度切分并写出文本，逐行扣减 y */
    private void writeWrapped(float x, float fontSize, boolean bold, String text, float rightBound) throws Exception {
        if (text == null || text.isEmpty()) {
            ensureSpace();
            y -= (fontSize + 6);
            return;
        }
        float maxWidth = rightBound - x;
        StringBuilder lineBuf = new StringBuilder();
        float currentWidth = 0f;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            float charWidth = charWidth(c, fontSize);
            if (charWidth < 0) {
                // 字体不支持该字形（如 U+2022 在 GBK 黑体中缺字），替换为 '?' 防止导出失败
                c = '?';
                charWidth = charWidth(c, fontSize);
            }
            if (currentWidth + charWidth > maxWidth && lineBuf.length() > 0) {
                writeOneLine(x, fontSize, bold, lineBuf.toString());
                lineBuf.setLength(0);
                currentWidth = 0f;
            }
            lineBuf.append(c);
            currentWidth += charWidth;
        }
        if (lineBuf.length() > 0) {
            writeOneLine(x, fontSize, bold, lineBuf.toString());
        }
    }

    /** 单行输出（含分页判断）
     * 注：PDF 端不做粗体模拟（FILL_STROKE 在严格 PDF 阅读器下偶发结构兼容性，
     *     且中文字体无内置 bold 变体），标题/正文/代码靠字号（14/11/9）+ 行距区分层级。 */
    private void writeOneLine(float x, float fontSize, boolean bold, String text) throws Exception {
        ensureSpace();
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        y -= (fontSize + 6);
    }

    private float textWidth(String text, float fontSize) {
        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            w += charWidth(text.charAt(i), fontSize);
        }
        return w;
    }

    /** 单字符宽度估算；字体不支持该字形时返回 -1（调用方替换为 '?'） */
    private float charWidth(char c, float fontSize) {
        try {
            return font.getStringWidth(String.valueOf(c)) / 1000f * fontSize;
        } catch (Exception e) {
            return -1;
        }
    }

    private void ensureSpace() throws Exception {
        if (y < MIN_Y) {
            newPage();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }

    /** 代码行保留缩进，仅换行转为空格（PDF 单行绘制） */
    private static String safeCode(String s) {
        return s == null ? "" : s.replaceAll("\\r\\n", "\n").replaceAll("\\n", "");
    }
}