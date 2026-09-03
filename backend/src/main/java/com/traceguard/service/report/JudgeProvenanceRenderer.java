package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A1 判定溯源：报告「判定溯源与引擎决策分布」章节。
 *
 * 把 LLM 二审的引擎决策分布（GAP-046 候选复核 + FUN-04b 双评审共识/仲裁/否决）
 * 汇总进 Word/PDF 报告，使"AI 如何参与判定"在正式交付物中可核验；
 * 规则模式（无 LLM）渲染规则口径说明，不虚构任何 LLM 数据。
 */
@Component
public class JudgeProvenanceRenderer implements ReportSectionRenderer {

    /** 决策路径 -> 中文标签（与前端 Defects.vue/Results.vue 口径一致） */
    private static final Map<String, String> PATH_LABELS = new LinkedHashMap<>();

    static {
        PATH_LABELS.put("RULE", "规则引擎（未启用 LLM）");
        PATH_LABELS.put("NOT_REVIEWED", "规则判定（候选复核未选中）");
        PATH_LABELS.put("RULE_FALLBACK", "规则兜底（LLM 评审失败保留）");
        PATH_LABELS.put("LLM_CONSENSUS_CONSISTENT", "LLM 共识·判一致");
        PATH_LABELS.put("LLM_CONSENSUS_DEFECT", "LLM 共识·判缺陷");
        PATH_LABELS.put("LLM_ARBITRATION_DEFECT", "分歧仲裁·判缺陷");
        PATH_LABELS.put("LLM_ARBITRATION_KEEP", "分歧仲裁·保一致");
        PATH_LABELS.put("RULE_QUANTIFY_VETO", "规则量化边界否决");
        PATH_LABELS.put("LLM_OWNER_OVERRIDE", "数值归属修正");
        PATH_LABELS.put("LLM_SINGLE", "LLM 单阶段判定");
        PATH_LABELS.put("LLM_SIM_GATE", "相似度兜底");
        PATH_LABELS.put("LLM", "LLM 判定（未细分）");
    }

    @Override
    public String key() {
        return "judge-provenance";
    }

    @Override
    public String defaultTitle() {
        return "判定溯源与引擎决策分布";
    }

    @Override
    public void renderWord(ReportContext ctx, XWPFDocument doc, String sectionTitle, int sectionNo) {
        ReportWordStyles.addHeading(doc, ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle, 14);
        Map<String, Object> stats = ctx.judgeStats;
        if (stats == null || toLong(stats.get("total")) == 0) {
            ReportWordStyles.addParagraph(doc,
                    "本次分析由规则引擎独立产出（未启用 LLM 增强或无匹配对），"
                            + "一致性结论基于三维相似度评分（语义/约束/不变量）与风险信号加权，"
                            + "不存在 LLM 参与判定记录。");
            ReportWordStyles.addSpacer(doc);
            return;
        }
        ReportWordStyles.addLabelValue(doc, "匹配对总数：", String.valueOf(stats.get("total")), null);
        ReportWordStyles.addLabelValue(doc, "LLM 复核对数：", String.valueOf(stats.get("llmReviewed")),
                ReportWordStyles.COLOR_HEADING);
        ReportWordStyles.addLabelValue(doc, "规则判定保留数：", String.valueOf(stats.get("ruleRetained")), null);
        ReportWordStyles.addParagraph(doc, "按决策路径分布：", 11, true);
        Object byPathObj = stats.get("byPath");
        if (byPathObj instanceof Map<?, ?> byPath) {
            for (Map.Entry<?, ?> e : byPath.entrySet()) {
                String path = String.valueOf(e.getKey());
                String label = PATH_LABELS.getOrDefault(path, path);
                ReportWordStyles.addListItem(doc, label + "：" + e.getValue() + " 对");
            }
        }
        ReportWordStyles.addParagraph(doc,
                "口径说明：双评审（变体A 锚定准则 / 变体B 精简准则）结论一致时采纳共识；分歧时以规则风险分仲裁；"
                        + "模型共识判一致但量化边界证据成立时由规则否决；数值归属错误由硬过滤修正；"
                        + "LLM 评审失败的对自动保留规则判定（安全降级）。候选复核架构（GAP-046）仅评审"
                        + "规则可疑/灰色带/高风险/探针抽检对，硬上限 200 对，保障万行级耗时可控。", 9, false);
        ReportWordStyles.addSpacer(doc);
    }

    @Override
    public void renderPdf(ReportContext ctx, PdfCursorAdapter cursor, String sectionTitle, int sectionNo) throws Exception {
        cursor.sectionTitle(ReportSectionRenderer.chineseNumber(sectionNo) + "、" + sectionTitle);
        Map<String, Object> stats = ctx.judgeStats;
        if (stats == null || toLong(stats.get("total")) == 0) {
            cursor.line(10, false,
                    "本次分析由规则引擎独立产出（未启用 LLM 增强或无匹配对），"
                            + "一致性结论基于三维相似度评分与风险信号加权，不存在 LLM 参与判定记录。");
            cursor.spacer();
            return;
        }
        cursor.labelValue("匹配对总数：", String.valueOf(stats.get("total")));
        cursor.labelValue("LLM 复核对数：", String.valueOf(stats.get("llmReviewed")));
        cursor.labelValue("规则判定保留数：", String.valueOf(stats.get("ruleRetained")));
        cursor.line(11, true, "按决策路径分布：");
        Object byPathObj = stats.get("byPath");
        if (byPathObj instanceof Map<?, ?> byPath) {
            for (Map.Entry<?, ?> e : byPath.entrySet()) {
                String path = String.valueOf(e.getKey());
                String label = PATH_LABELS.getOrDefault(path, path);
                cursor.indent(10, label + "：" + e.getValue() + " 对");
            }
        }
        cursor.indent(9,
                "口径说明：双评审结论一致时采纳共识；分歧以规则风险分仲裁；量化边界确定性证据可否决一致结论；"
                        + "数值归属错误由硬过滤修正；LLM 失败对保留规则判定。候选复核（GAP-046）硬上限 200 对。");
        cursor.spacer();
    }

    private long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 供测试断言的标签映射（只读视图） */
    static Map<String, String> pathLabels() {
        return new LinkedHashMap<>(PATH_LABELS);
    }
}
