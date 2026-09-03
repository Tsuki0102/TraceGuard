package com.traceguard.service.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 判定溯源：报告「判定溯源与引擎决策分布」章节渲染回归。
 * 覆盖：LLM 模式分布渲染、规则模式口径说明（不虚构 LLM 数据）、Word 全文可断言。
 */
@DisplayName("A1 JudgeProvenanceRenderer 报告溯源章节回归")
class JudgeProvenanceRendererTest {

    private final JudgeProvenanceRenderer renderer = new JudgeProvenanceRenderer();

    private String renderWordToString(Map<String, Object> judgeStats) throws Exception {
        ReportContext ctx = new ReportContext(null, new LinkedHashMap<>(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), judgeStats);
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayInputStream in = new ByteArrayInputStream(render(doc, ctx));
             org.apache.poi.xwpf.extractor.XWPFWordExtractor ex =
                     new org.apache.poi.xwpf.extractor.XWPFWordExtractor(new XWPFDocument(in))) {
            return ex.getText();
        }
    }

    private byte[] render(XWPFDocument doc, ReportContext ctx) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        renderer.renderWord(ctx, doc, renderer.defaultTitle(), 3);
        doc.write(out);
        return out.toByteArray();
        // 注意：调用方需在 try-with-resources 中关闭 in/ex，此处仅输出字节
    }

    @Test
    @DisplayName("key/defaultTitle 与注册表约定一致")
    void keyContract() {
        assertThat(renderer.key()).isEqualTo("judge-provenance");
        assertThat(renderer.defaultTitle()).isEqualTo("判定溯源与引擎决策分布");
        assertThat(JudgeProvenanceRenderer.pathLabels()).containsKeys(
                "RULE", "NOT_REVIEWED", "LLM_CONSENSUS_CONSISTENT", "LLM_ARBITRATION_DEFECT",
                "RULE_QUANTIFY_VETO", "LLM_OWNER_OVERRIDE");
    }

    @Test
    @DisplayName("LLM 模式：渲染总数/复核数/规则保留数与分路径计数及口径说明")
    void rendersDistribution() throws Exception {
        Map<String, Object> byPath = new LinkedHashMap<>();
        byPath.put("LLM_CONSENSUS_CONSISTENT", 41);
        byPath.put("LLM_ARBITRATION_DEFECT", 2);
        byPath.put("RULE_QUANTIFY_VETO", 1);
        byPath.put("NOT_REVIEWED", 11);
        Map<String, Object> judgeStats = new LinkedHashMap<>();
        judgeStats.put("total", 55);
        judgeStats.put("llmReviewed", 44);
        judgeStats.put("ruleRetained", 12);
        judgeStats.put("byPath", byPath);

        String text = renderWordToString(judgeStats);
        assertThat(text).contains("判定溯源与引擎决策分布");
        assertThat(text).contains("匹配对总数：55");
        assertThat(text).contains("LLM 复核对数：44");
        assertThat(text).contains("规则判定保留数：12");
        assertThat(text).contains("LLM 共识·判一致：41 对");
        assertThat(text).contains("分歧仲裁·判缺陷：2 对");
        assertThat(text).contains("规则量化边界否决：1 对");
        assertThat(text).contains("规则判定（候选复核未选中）：11 对");
        assertThat(text).contains("GAP-046");
    }

    @Test
    @DisplayName("规则模式：只渲染规则口径说明，不虚构 LLM 数据")
    void rendersRuleModeHint() throws Exception {
        String text = renderWordToString(null);
        assertThat(text).contains("规则引擎独立产出");
        assertThat(text).contains("不存在 LLM 参与判定记录");
        assertThat(text).doesNotContain("LLM 复核对数");

        String empty = renderWordToString(new LinkedHashMap<>(Map.of("total", 0)));
        assertThat(empty).contains("规则引擎独立产出");
    }
}
