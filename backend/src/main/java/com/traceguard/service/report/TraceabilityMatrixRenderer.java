package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GAP-010：章节渲染器 - 追溯矩阵（限量展示 + 完整导出提示）
 */
@Component
public class TraceabilityMatrixRenderer implements ReportSectionRenderer {

    private static final int MAX_MATRIX_ROWS_IN_REPORT = 50;

    @Override
    public String key() {
        return "traceability-matrix";
    }

    @Override
    public String defaultTitle() {
        return "追溯矩阵";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        List<Map<String, Object>> matrix = ctx.traceabilityMatrix;
        addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        int limit = Math.min(matrix.size(), MAX_MATRIX_ROWS_IN_REPORT);
        int midx = 1;
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = matrix.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(midx++).append(". ").append(row.getOrDefault("requirementId", "-"))
                    .append(" [").append(mapStatusText(row)).append("]");
            Object sim = row.get("similarity");
            if (sim != null) {
                sb.append(" 相似度").append(String.format("%.1f%%", ((Double) sim) * 100));
            }
            Object startLine = row.get("startLine");
            if (startLine != null) {
                sb.append(" 代码位置：").append(row.getOrDefault("filePath", "-"))
                        .append(" 第").append(startLine).append("行");
            } else {
                sb.append(" 代码位置：未关联代码");
            }
            ReportWordStyles.addItemDetail(doc, sb.toString());
        }
        if (matrix.isEmpty()) {
            ReportWordStyles.addParagraph(doc, "暂无追溯矩阵数据。");
        } else if (matrix.size() > MAX_MATRIX_ROWS_IN_REPORT) {
            ReportWordStyles.addParagraph(doc, "共" + matrix.size() + "条追溯关系，此处展示前" + MAX_MATRIX_ROWS_IN_REPORT
                    + "条，完整矩阵请通过「追溯矩阵」页面导出Excel查看。");
        }
        ReportWordStyles.addSpacer(doc);
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        List<Map<String, Object>> matrix = ctx.traceabilityMatrix;
        cursor.sectionTitle(ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        int limit = Math.min(matrix.size(), MAX_MATRIX_ROWS_IN_REPORT);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = matrix.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(i + 1).append(". ").append(row.getOrDefault("requirementId", "-"))
                    .append(" [").append(mapStatusText(row)).append("]");
            Object sim = row.get("similarity");
            if (sim != null) {
                sb.append(" ").append(String.format("%.1f%%", ((Double) sim) * 100));
            }
            Object startLine = row.get("startLine");
            if (startLine != null) {
                sb.append(" ").append(row.getOrDefault("filePath", "-")).append(":L").append(startLine);
            }
            cursor.itemDetail(sb.toString());
        }
        if (matrix.isEmpty()) {
            cursor.line(11, false, "暂无追溯矩阵数据。");
        } else if (matrix.size() > MAX_MATRIX_ROWS_IN_REPORT) {
            cursor.line(11, false, "共" + matrix.size() + "条，展示前" + MAX_MATRIX_ROWS_IN_REPORT
                    + "条，完整矩阵请导出Excel查看。");
        }
        cursor.spacer();
    }

    private String mapStatusText(Map<String, Object> row) {
        String status = String.valueOf(row.getOrDefault("status", ""));
        String consistency = String.valueOf(row.getOrDefault("consistencyStatus", "null"));
        if ("missing".equals(status)) return "需求缺失";
        switch (consistency) {
            case "consistent": return "完全一致";
            case "general_inconsistent": return "一般不一致";
            case "serious_inconsistent": return "严重不一致";
            default: return "未覆盖";
        }
    }

    private void addHeading(XWPFDocument doc, String text, int fontSize) {
        ReportWordStyles.addHeading(doc, text, fontSize);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        ReportWordStyles.addParagraph(doc, text);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }
}
