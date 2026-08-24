package com.traceguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.entity.Requirement;
import com.traceguard.util.AlloySpecVerifierUtil;
import com.traceguard.util.RequirementAnalyzerUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-007：需求->形式化规约转换质量评测（转换准确率）
 *
 * 口径（设计方案 3.9.6 步骤 2）：
 *   单条达标 = ① 生成规约真实校验未失败（AlloySpecVerifierUtil.verify status 非 failed；
 *              按 GAP-013 口径 warning 不阻断，passed/warning 均可进入后续流程）
 *              且 ② 关键要素覆盖率 >= 0.8（referenceAlloy 的 keyElements 在生成规约中的命中率）
 *   转换准确率 = 达标条数 / N            （目标 >= 85%）
 *
 * 评测隔离（3.9.7）：本类自包含运行（独立构建 Requirement + 生成 + 校验），
 * 不依赖前端与上传链路；Alloy 不可用时自动降级结构校验并如实记录 engine。
 * 评测结果写入 docs/03-报告/评测报告-综合.md（spec-conversion 章节）。
 */
@DisplayName("GAP-007 规约转换质量评测（转换准确率）")
class SpecConversionEvalTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final RequirementAnalyzerUtil ANALYZER = new RequirementAnalyzerUtil();

    private static List<JsonNode> pairs;

    @BeforeAll
    static void loadPairs() throws Exception {
        Path file = resolveDataset("spec-conversion-pairs.json");
        JsonNode root = OM.readTree(Files.readString(file));
        pairs = new ArrayList<>();
        root.path("pairs").forEach(pairs::add);
        assertFalse(pairs.isEmpty(), "spec-conversion-pairs.json 未包含任何条目");
    }

    static Path resolveDataset(String name) {
        List<Path> candidates = List.of(
                Path.of("../samples/dataset", name),
                Path.of("../../samples/dataset", name),
                Path.of("samples/dataset", name));
        for (Path p : candidates) {
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("找不到数据集文件: " + name + "（请在仓库根目录执行 mvn test）");
    }

    @Test
    @DisplayName("转换准确率（达标 = 校验未失败 且 关键要素覆盖率>=0.8）")
    void conversionAccuracy() throws Exception {
        int passed = 0;
        int passedSemantic = 0;
        int evaluated = 0;
        double coverageSum = 0;
        double semanticSum = 0;
        Map<String, Integer> statusDist = new java.util.TreeMap<>();
        List<String[]> detail = new ArrayList<>(); // [id, status, literalCov, semanticCov, ok]

        for (JsonNode pair : pairs) {
            String id = pair.path("id").asText();
            String reqText = pair.path("requirementText").asText();
            List<String> keyElements = new ArrayList<>();
            pair.path("keyElements").forEach(e -> keyElements.add(e.asText()));

            // 需求解析 + 规则模板生成 Alloy（LLM 模式可在此注入 llmService 生成结果，本评测默认规则模式）
            List<Requirement> reqs = ANALYZER.analyzeRequirements(0L, List.of(reqText));
            if (reqs.isEmpty()) {
                detail.add(new String[]{id, "req-empty", "0.00", "0.00", "false"});
                continue;
            }
            String alloyCode = ANALYZER.generateAlloySpec(reqs.get(0));

            // 真实校验（Alloy 不可用自动降级结构校验，engine 字段如实记录）
            AlloySpecVerifierUtil.VerifyResult vr = AlloySpecVerifierUtil.verify(alloyCode);
            statusDist.merge(vr.status(), 1, Integer::sum);
            double coverage = EvalMetrics.keyElementCoverage(alloyCode, keyElements);
            double semCov = EvalMetrics.semanticCoverage(alloyCode);
            coverageSum += coverage;
            semanticSum += semCov;
            evaluated++;
            // 达标（字面口径，保持历史可比）：校验未失败 且 关键要素覆盖率 >= 0.8
            boolean ok = !"failed".equals(vr.status()) && coverage >= 0.8;
            // 达标（语义口径，GAP-052 主口径）：校验未失败 且 语义要素覆盖率 >= 0.8
            boolean okSem = !"failed".equals(vr.status()) && semCov >= 0.8;
            if (ok) passed++;
            if (okSem) passedSemantic++;
            detail.add(new String[]{id, vr.status() + "/" + vr.getEngine(),
                    String.format("%.2f", coverage), String.format("%.2f", semCov), String.valueOf(ok)});
        }

        double accuracy = EvalMetrics.conversionAccuracy(passed, pairs.size());
        double accuracySemantic = EvalMetrics.conversionAccuracy(passedSemantic, pairs.size());
        double avgCoverage = evaluated > 0 ? coverageSum / evaluated : 0;
        double avgSemantic = evaluated > 0 ? semanticSum / evaluated : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(EvalReportWriter.envSnapshot("规则模式", "N/A（规则模板生成）", "关闭"));
        sb.append("\n| 指标 | 值 | 目标 | 判定 |\n|---|---|---|---|\n");
        sb.append("| **转换准确率（字面口径·历史基线）** | ").append(EvalReportWriter.pct(accuracy)).append(" | >= 85% | ")
          .append(accuracy >= 0.85 ? "达标" : "未达标").append(" |\n");
        sb.append("| **转换准确率（语义口径·GAP-052 主）** | ").append(EvalReportWriter.pct(accuracySemantic)).append(" | >= 85% | ")
          .append(accuracySemantic >= 0.85 ? "达标" : "未达标").append(" |\n");
        sb.append("| 关键要素平均覆盖率（字面） | ").append(EvalReportWriter.pct(avgCoverage)).append(" | >= 80% | ")
          .append(avgCoverage >= 0.8 ? "达标" : "未达标").append(" |\n");
        sb.append("| 平均语义要素覆盖率 | ").append(EvalReportWriter.pct(avgSemantic)).append(" | >= 80% | ")
          .append(avgSemantic >= 0.8 ? "达标" : "未达标").append(" |\n");
        sb.append("| 样本数 N | ").append(pairs.size()).append(" | 40~60 | ").append(pairs.size() >= 40 ? "达标" : "偏少").append(" |\n");
        sb.append("\n### 校验状态分布\n\n| 状态 | 条数 |\n|---|---|\n");
        statusDist.forEach((s, c) -> sb.append("| ").append(s).append(" | ").append(c).append(" |\n"));
        sb.append("\n> 达标口径说明：按 GAP-013 口径，warning 不阻断（passed/warning 均可进入后续流程）。")
          .append("「字面口径」= 校验未失败 且 关键要素(字面 token)覆盖率>=0.8（历史基线口径）；")
          .append("「语义口径」= 校验未失败 且 语义要素覆盖率>=0.8（GAP-052 修正口径，对规则/LLM 公平）。\n\n");
        sb.append("### 差距分析与调优建议\n\n");
        sb.append("- 存在 ").append(statusDist.getOrDefault("failed", 0))
          .append(" 条生成规约未通过结构校验（failed），多为状态/转移提取不完整或初始状态未落入状态集，"
          + "建议增强 generateAlloySpec 的 Kripke 要素兜底与状态名清洗。\n");
        sb.append("- 覆盖率未达 0.8 的条目集中在关键语义 token 未出现在生成规约中，"
          + "建议扩充中英词典（GAP-024）并优化约束/不变量子句映射。\n");
        sb.append("- 该结果为规则模式基线（LLM 关闭）；配置 LLM/Embedding key 后按分模型（GLM 5.3 / DeepSeek V4 Flash / 千问 qwen-max）"
          + "各跑一轮并分列对比，见 SpecConversionLlmEvalTest 生成的 spec-conversion-* 章节（设计 11.3 节）。\n\n");
        sb.append("### 未达标样本明细（前 20 条）\n\n");
        sb.append("| ID | 校验状态/引擎 | 字面覆盖率 | 语义覆盖率 | 达标 |\n|---|---|---|---|---|\n");
        detail.stream().filter(d -> "false".equals(d[4])).limit(20)
                .forEach(d -> sb.append("| ").append(d[0]).append(" | ").append(d[1]).append(" | ")
                        .append(d[2]).append(" | ").append(d[3]).append(" | 否 |\n"));
        if (detail.stream().noneMatch(d -> "false".equals(d[4]))) {
            sb.append("（全部达标）\n");
        }
        EvalReportWriter.writeSection("spec-conversion", "一、规约转换评测（转换准确率）", sb.toString());

        System.out.println("[GAP-007 spec] 转换准确率(字面) = " + EvalReportWriter.pct(accuracy)
                + "（达标 " + passed + "/" + pairs.size() + "），语义准确率 = " + EvalReportWriter.pct(accuracySemantic)
                + "（达标 " + passedSemantic + "/" + pairs.size() + "），平均字面覆盖率 = " + EvalReportWriter.pct(avgCoverage)
                + "，平均语义覆盖率 = " + EvalReportWriter.pct(avgSemantic)
                + "，状态分布=" + statusDist);

        // 软校验：指标是否达标记录于报告，不因未达标硬失败（设计 3.9.11 #4：未达标如实记录+差距分析）
        assertTrue(accuracy >= 0 && accuracy <= 1, "转换准确率应为合法概率值");
        assertTrue(evaluated > 0, "评测应至少成功评估 1 条样本");
    }

    @Test
    @DisplayName("关键要素覆盖率计算正确性（构造样例）")
    void coverageFormula() {
        assertTrue(EvalMetrics.keyElementCoverage("module req_0001 sig Order { state: one State }",
                List.of("module", "sig Order", "state: one State")) >= 0.99);
        assertTrue(EvalMetrics.keyElementCoverage("module x sig Y", List.of("module", "not-present-token")) < 1.0);
        assertTrue(EvalMetrics.keyElementCoverage("", List.of("module")) == 0.0);
    }
}
