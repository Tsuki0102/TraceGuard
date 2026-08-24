package com.traceguard.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * GAP-010：PDF 输出游标适配器（从 ExportService.PdfCursor 提取）
 * 统一处理自动分页，供各章节渲染器复用
 */
public class PdfCursorAdapter {
    private final PDDocument doc;
    private final PDType0Font font;
    private PDPageContentStream cs;
    private float y;

    public PdfCursorAdapter(PDDocument doc, PDType0Font font) throws Exception {
        this.doc = doc;
        this.font = font;
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        this.cs = new PDPageContentStream(doc, page);
        this.y = PDRectangle.A4.getHeight() - 50f;
    }

    /** 写一行文字（空间不足自动分页） */
    public void line(int fontSize, boolean bold, String text) throws Exception {
        writeAt(50f, fontSize, text);
    }

    /** 写一行缩进文字（空间不足自动分页） */
    public void indent(int fontSize, String text) throws Exception {
        writeAt(62f, fontSize, text);
    }

    /** 章节间空隙 */
    public void gap() {
        y -= 9f;
    }

    public void close() throws Exception {
        cs.close();
    }

    private void writeAt(float x, int fontSize, String text) throws Exception {
        ensureSpace();
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setLeading(fontSize + 4);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        y -= (fontSize + 8);
    }

    private void ensureSpace() throws Exception {
        if (y < 74f) {
            cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - 50f;
        }
    }
}
