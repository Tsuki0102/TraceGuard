package com.traceguard.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * GAP-010：报告章节渲染器接口
 * 每个实现对应一个 sectionKey，提供 Word 和 PDF 两种渲染方式
 */
public interface ReportSectionRenderer {

    /** 章节唯一标识（如 project-overview、stats-summary） */
    String key();

    /** 章节默认中文标题 */
    String defaultTitle();

    /** 中文序号映射（一、二、三...） */
    String[] SECTION_NUMBERS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** 将序号（1-based）转为中文数字 */
    static String chineseNumber(int n) {
        return n >= 1 && n <= SECTION_NUMBERS.length ? SECTION_NUMBERS[n - 1] : String.valueOf(n);
    }

    /**
     * 渲染 Word 章节
     * @param ctx 报告上下文（一次查询，多章节复用）
     * @param doc POI Word 文档对象
     * @param sectionTitle 章节标题（模板自定义标题或默认标题）
     * @param sectionNo 章节序号（1-based）
     */
    void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) throws Exception;

    /**
     * 渲染 PDF 章节
     * @param ctx 报告上下文
     * @param cursor PDF 输出游标（含自动分页）
     * @param sectionTitle 章节标题
     * @param sectionNo 章节序号（1-based）
     */
    void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception;
}
