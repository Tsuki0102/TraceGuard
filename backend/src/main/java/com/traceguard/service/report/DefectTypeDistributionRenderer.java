package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GAP-010：章节渲染器 - 缺陷类型分布（GAP-020 四类口径）
 */
@Component
public class DefectTypeDistributionRenderer implements ReportSectionRenderer {

    @Override
    public String key() {
        return "defect-type-distribution";
    }

    @Override
    public String defaultTitle() {
        return "缺陷类型分布";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        Object typeDist = ctx.stats.get("defectTypeDistribution");
        if (typeDist instanceof Map && !((Map<?, ?>) typeDist).isEmpty()) {
            addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) typeDist).entrySet()) {
                // 用全角圆点 ●（U+25CF，GBK 中文字体均含），避免 U+2022 • 在 PDF 内嵌字体缺字形报 400
                ReportWordStyles.addParagraph(doc, "● " + entry.getKey() + "：" + entry.getValue() + "个", 11, true);
            }
            ReportWordStyles.addSpacer(doc);
        }
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        Object typeDist = ctx.stats.get("defectTypeDistribution");
        if (typeDist instanceof Map && !((Map<?, ?>) typeDist).isEmpty()) {
            cursor.sectionTitle(ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) typeDist).entrySet()) {
                cursor.line(11, false, "● " + entry.getKey() + "：" + entry.getValue() + "个");
            }
            cursor.spacer();
        }
    }

    private void addHeading(XWPFDocument doc, String text, int fontSize) {
        ReportWordStyles.addHeading(doc, text, fontSize);
    }
}
