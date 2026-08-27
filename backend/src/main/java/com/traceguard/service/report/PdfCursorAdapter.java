package com.traceguard.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * GAP-010：PDF 输出游标适配器
 * 统一处理自动换行 + 自动分页，供各章节渲染器复用
 *
 * 关键修复：
 * - 按字符宽度估算 + 可用宽度，按行切分长文本，避免内容超出页边框
 * - 章节/正文/缩进三层边距分明（标题 0 / 正文 16 / 缩进 32）
 * - 行距 = 字号 + 6，避免行间粘连
 * - 底部余量 60pt，避免底部贴边
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

    /** 缩进阶梯（pt）：标题行 0 / 正文 16 / 子项 32 */
    private static final float INDENT_BODY = 16f;
    private static final float INDENT_SUB = 32f;

    private final PDDocument doc;
    private final PDType0Font font;
    private PDPageContentStream cs;
    private float y;

    public PdfCursorAdapter(PDDocument doc, PDType0Font font) throws Exception {
        this.doc = doc;
        this.font = font;
        newPage();
    }

    /** 写一行正文（自动按宽度换行） */
    public void line(int fontSize, boolean bold, String text) throws Exception {
        writeWrapped(LEFT_MARGIN, fontSize, bold, safe(text), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 写一行缩进正文（自动按宽度换行） */
    public void indent(int fontSize, String text) throws Exception {
        writeWrapped(LEFT_MARGIN + INDENT_BODY, fontSize, false, safe(text), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 写一行更深缩进的子项（自动按宽度换行） */
    public void subIndent(int fontSize, String text) throws Exception {
        writeWrapped(LEFT_MARGIN + INDENT_SUB, fontSize, false, safe(text), A4_WIDTH - RIGHT_MARGIN);
    }

    /** 章节间空隙 */
    public void gap() {
        y -= 8f;
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

    /** 按可用宽度切分并写出文本，逐行扣减 y，分页阈值触发自动换页 */
    private void writeWrapped(float x, int fontSize, boolean bold, String text, float rightBound) throws Exception {
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
            // 超过可用宽度则换行
            if (currentWidth + charWidth > maxWidth && lineBuf.length() > 0) {
                writeOneLine(x, fontSize, lineBuf.toString());
                lineBuf.setLength(0);
                currentWidth = 0f;
            }
            lineBuf.append(c);
            currentWidth += charWidth;
        }
        if (lineBuf.length() > 0) {
            writeOneLine(x, fontSize, lineBuf.toString());
        }
    }

    /** 单行输出（含分页判断） */
    private void writeOneLine(float x, int fontSize, String text) throws Exception {
        ensureSpace();
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        y -= (fontSize + 6);
    }

    /** 单字符宽度估算（PDType0Font 走 CMap 真实度量；异常时按字号近似） */
    private float charWidth(char c, int fontSize) {
        try {
            return font.getStringWidth(String.valueOf(c)) / 1000f * fontSize;
        } catch (Exception e) {
            // ASCII 字符按 0.55 字号估算，中文/全角按 1.0 字号
            return c < 0x7F ? fontSize * 0.55f : fontSize;
        }
    }

    /** 剩余空间不足时自动分页 */
    private void ensureSpace() throws Exception {
        if (y < MIN_Y) {
            newPage();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}