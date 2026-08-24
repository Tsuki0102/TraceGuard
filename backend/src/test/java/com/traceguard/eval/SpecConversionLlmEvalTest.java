package com.traceguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import com.traceguard.entity.Requirement;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.ModelRouter;
import com.traceguard.llm.Stage;
import com.traceguard.service.LlmService;
import com.traceguard.util.AlloySpecVerifierUtil;
import com.traceguard.util.RequirementAnalyzerUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-052：转换准确率 LLM 分模型对比复测（规则模式 vs 千问 qwen-max vs 本地 CodeLlama）。
 *
 * 口径与 SpecConversionEvalTest 完全一致（达标 = 校验未失败 且 关键要素覆盖率 >= 0.8）。
 * 不同点：本类在 LLM 启用时优先用 LlmService.generateAlloy（需求文本 + Kripke 结构）生成 Alloy，
 * 失败/返回 null 则回退规则模板 generateAlloySpec，从而真实衡量「大模型路径」的转换准确率。
 *
 * 设计要点：
 *  - 不改动任何 YAML / application 配置；qwen api-key 仅从环境变量 QWEN_API_KEY 读取，绝不落盘。
 *  - 纯手动装配 LlmService / LlmCallExecutor / ModelRouter（无 Spring 上下文、无数据库依赖）。
 *  - qwen 模式：无 QWEN_API_KEY 环境变量时跳过（assumeTrue），避免无 key 环境报错。
 *  - codellama 模式：探测本机 Ollama(:11434) 不可用则跳过。
 */
@DisplayName("GAP-052 规约转换准确率 LLM 分模型复测")
class SpecConversionLlmEvalTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final RequirementAnalyzerUtil ANALYZER = new RequirementAnalyzerUtil();

    private static List<JsonNode> pairs;
    private static Path datasetFile;

    @BeforeAll
    static void loadPairs() throws Exception {
        datasetFile = resolveDataset("spec-conversion-pairs.json");
        JsonNode root = OM.readTree(Files.readString(datasetFile));
        pairs = new ArrayList<>();
        root.path("pairs").forEach(pairs::add);
        assertTrue(!pairs.isEmpty(), "spec-conversion-pairs.json 未包含任何条目");
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

    // ===================== 规则模式基线（与 SpecConversionEvalTest 一致） =====================

    @Test
    @DisplayName("规则模式基线（无 LLM）：转换准确率")
    void ruleBaseline() throws Exception {
        runMode("规则模式（基线）", "N/A（规则模板生成）", null, "rule-baseline");
    }

    // ===================== 千问 qwen-max（统一评测模型） =====================

    @Test
    @DisplayName("GAP-052 LLM 路径：阿里千问 qwen-max 转换准确率")
    void qwenMax() throws Exception {
        String apiKey = System.getenv("QWEN_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.trim().isEmpty(),
                "未设置环境变量 QWEN_API_KEY，跳过千问复测（不影响其他模式）");

        // 统一评测模型：QWEN_MODEL 环境变量可覆盖，默认 qwen-max（用户约定 LLM 评测均用该模型）
        String model = System.getenv("QWEN_MODEL");
        if (model == null || model.trim().isEmpty()) { model = "qwen-max"; }
        LlmService svc = buildLlmService("qwen",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey.trim(), model);
        runMode("LLM 路径（千问 " + model + "）", model, svc, "qwen-max");
    }

    // ===================== 本地 CodeLlama =====================

    @Test
    @DisplayName("GAP-052 LLM 路径：本地 Ollama CodeLlama 转换准确率")
    void codeLlama() throws Exception {
        boolean ollamaUp = isOllamaUp();
        Assumptions.assumeTrue(ollamaUp,
                "本机 Ollama(:11434) 不可用，跳过 CodeLlama 复测（不影响其他模式）");

        // 本地模型无需 api-key（占位即可），base-url 指向本机即视为可用
        LlmService svc = buildLlmService("codellama",
                "http://localhost:11434/v1", "", "codellama:7b");
        runMode("LLM 路径（本地 CodeLlama 7B）", "codellama:7b", svc, "codellama");
    }

    // ===================== 通用评测逻辑 =====================

    private void runMode(String modeLabel, String modelLabel, LlmService llmService, String sectionId) throws Exception {
        int passed = 0;
        int passedLiteral = 0;
        int evaluated = 0;
        double coverageSum = 0;       // 语义覆盖率（主口径）
        double literalSum = 0;        // 字面覆盖率（旧口径，并列追溯）
        Map<String, Integer> statusDist = new TreeMap<>();
        List<String[]> detail = new ArrayList<>();
        int llmUsed = 0, llmFallback = 0;

        boolean useLlm = llmService != null && llmService.isEnabled();
        for (JsonNode pair : pairs) {
            String id = pair.path("id").asText();
            String reqText = pair.path("requirementText").asText();
            List<String> keyElements = new ArrayList<>();
            pair.path("keyElements").forEach(e -> keyElements.add(e.asText()));

            List<Requirement> reqs = ANALYZER.analyzeRequirements(0L, List.of(reqText));
            if (reqs.isEmpty()) {
                detail.add(new String[]{id, "req-empty", "0.00", "0.00", "false", "rule"});
                continue;
            }
            Requirement req = reqs.get(0);

            String alloyCode;
            String engine;
            if (useLlm) {
                try {
                    String llmAlloy = llmService.generateAlloy(req.getOriginalText(), parseKripke(req));
                    if (llmAlloy != null && !llmAlloy.trim().isEmpty()) {
                        alloyCode = llmAlloy;
                        engine = modelLabel + " (LLM)";
                        llmUsed++;
                    } else {
                        alloyCode = ANALYZER.generateAlloySpec(req);
                        engine = "rule (LLM 回退)";
                        llmFallback++;
                    }
                } catch (Exception e) {
                    alloyCode = ANALYZER.generateAlloySpec(req);
                    engine = "rule (LLM 异常回退)";
                    llmFallback++;
                }
            } else {
                alloyCode = ANALYZER.generateAlloySpec(req);
                engine = "rule";
            }

            AlloySpecVerifierUtil.VerifyResult vr = AlloySpecVerifierUtil.verify(alloyCode);
            statusDist.merge(vr.status(), 1, Integer::sum);
            double semCov = EvalMetrics.semanticCoverage(alloyCode);
            double litCov = EvalMetrics.keyElementCoverage(alloyCode, keyElements);
            coverageSum += semCov;
            literalSum += litCov;
            evaluated++;
            // 主口径（GAP-052 修正）：结构校验未失败 且 语义化要素覆盖率 >= 0.8（对规则/LLM 公平）
            boolean ok = !"failed".equals(vr.status()) && semCov >= 0.8;
            // 旧字面口径（并列追溯）
            boolean okLiteral = !"failed".equals(vr.status()) && litCov >= 0.8;
            if (ok) passed++;
            if (okLiteral) passedLiteral++;
            detail.add(new String[]{id, vr.status() + "/" + vr.getEngine(),
                    String.format("%.2f", semCov), String.format("%.2f", litCov),
                    String.valueOf(ok), engine});
        }

        double accuracy = EvalMetrics.conversionAccuracy(passed, pairs.size());
        double accuracyLiteral = EvalMetrics.conversionAccuracy(passedLiteral, pairs.size());
        double avgCoverage = evaluated > 0 ? coverageSum / evaluated : 0;
        double avgLiteral = evaluated > 0 ? literalSum / evaluated : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(EvalReportWriter.envSnapshot(modeLabel, modelLabel, "关闭"));
        sb.append("\n| 指标 | 值 | 目标 | 判定 |\n|---|---|---|---|\n");
        sb.append("| **转换准确率（语义口径·主）** | ").append(EvalReportWriter.pct(accuracy)).append(" | >= 85% | ")
                .append(accuracy >= 0.85 ? "达标" : "未达标").append(" |\n");
        sb.append("| 转换准确率（字面口径·旧·并列） | ").append(EvalReportWriter.pct(accuracyLiteral)).append(" | >= 85% | ")
                .append(accuracyLiteral >= 0.85 ? "达标" : "未达标").append(" |\n");
        sb.append("| 平均语义要素覆盖率 | ").append(EvalReportWriter.pct(avgCoverage)).append(" | >= 80% | ")
                .append(avgCoverage >= 0.8 ? "达标" : "未达标").append(" |\n");
        sb.append("| 平均字面覆盖率（旧·并列） | ").append(EvalReportWriter.pct(avgLiteral)).append(" | >= 80% | ")
                .append(avgLiteral >= 0.8 ? "达标" : "未达标").append(" |\n");
        sb.append("| 样本数 N | ").append(pairs.size()).append(" | 40~60 | ")
                .append(pairs.size() >= 40 ? "达标" : "偏少").append(" |\n");
        if (useLlm) {
            sb.append("| LLM 生成采用 / 回退规则 | ").append(llmUsed).append(" / ").append(llmFallback)
                    .append(" | - | - |\n");
        }
        sb.append("\n### 校验状态分布\n\n| 状态 | 条数 |\n|---|---|\n");
        statusDist.forEach((s, c) -> sb.append("| ").append(s).append(" | ").append(c).append(" |\n"));
        sb.append("\n> 达标口径（主）：校验状态非 failed 且**语义要素覆盖率 >= 0.8**；旧字面口径并列记录（GAP-013：warning 不阻断）。\n\n");
        sb.append("### 未达标样本明细（前 20 条）\n\n");
        sb.append("| ID | 校验状态/引擎 | 语义覆盖率 | 字面覆盖率 | 达标 | 引擎 |\n|---|---|---|---|---|---|\n");
        detail.stream().filter(d -> "false".equals(d[4])).limit(20)
                .forEach(d -> sb.append("| ").append(d[0]).append(" | ").append(d[1]).append(" | ")
                        .append(d[2]).append(" | ").append(d[3]).append(" | 否 | ").append(d[5]).append(" |\n"));
        if (detail.stream().noneMatch(d -> "false".equals(d[4]))) {
            sb.append("（全部达标）\n");
        }
        EvalReportWriter.writeSection("spec-conversion-" + sectionId,
                "一." + (useLlm ? "X" : "0") + " 规约转换评测（" + modeLabel + "）", sb.toString());

        System.out.println("[GAP-052 " + modeLabel + "] 转换准确率 = " + EvalReportWriter.pct(accuracy)
                + "（达标 " + passed + "/" + pairs.size() + "），平均覆盖率 = " + EvalReportWriter.pct(avgCoverage)
                + (useLlm ? "，LLM 采用/回退 = " + llmUsed + "/" + llmFallback : "")
                + "，状态分布=" + statusDist);

        // 软校验：不因未达标硬失败（如实记录即可）
        assertTrue(accuracy >= 0 && accuracy <= 1, "转换准确率应为合法概率值");
        assertTrue(evaluated > 0, "评测应至少成功评估 1 条样本");
    }

    // ===================== 装配与工具 =====================

    /** 手动装配 LlmService（含 executor / router），不依赖 Spring 上下文 */
    private LlmService buildLlmService(String providerKey, String baseUrl, String apiKey, String model) {
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl(baseUrl);
        pc.setApiKey(apiKey);
        pc.setTimeoutSeconds(120);
        props.getProviders().put(providerKey, pc);
        // 仅配置 alloy 环节走目标模型，其他环节无关（本评测只用 generateAlloy）
        props.getRouting().put(Stage.ALLOY.getConfigKey(), providerKey);
        props.getModels().put(Stage.ALLOY.getConfigKey(), model);
        props.setMaxConcurrentCalls(4);
        props.setMaxCallsPerStage(200);

        ModelRouter router = new ModelRouter(props);
        LlmCallExecutor executor = new LlmCallExecutor(props, router);
        LlmService svc = new LlmService();
        ReflectionTestUtils.setField(svc, "properties", props);
        ReflectionTestUtils.setField(svc, "executor", executor);
        ReflectionTestUtils.setField(svc, "router", router);
        return svc;
    }

    /** 从 Requirement 提取 Kripke 结构 JSON（供 LLM 生成 Alloy，对齐 AnalysisService.parseKripke） */
    private Object parseKripke(Requirement req) {
        Map<String, Object> kripke = new LinkedHashMap<>();
        if (req.getStateSet() != null) kripke.put("states", parseListField(req.getStateSet()));
        if (req.getStateTransitions() != null) kripke.put("transitions", parseTransitionList(req.getStateTransitions()));
        if (req.getAtomicConstraints() != null) kripke.put("constraints", parseListField(req.getAtomicConstraints()));
        if (req.getInvariants() != null) kripke.put("invariants", parseListField(req.getInvariants()));
        return kripke;
    }

    private List<String> parseListField(String listText) {
        List<String> result = new ArrayList<>();
        if (listText == null || listText.trim().isEmpty() || "[]".equals(listText.trim())) return result;
        String inner = listText.trim();
        if (inner.startsWith("[") && inner.endsWith("]")) inner = inner.substring(1, inner.length() - 1);
        for (String part : inner.split(",")) {
            String p = part.trim();
            if (p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
            if (!p.isEmpty()) result.add(p);
        }
        return result;
    }

    private List<String[]> parseTransitionList(String text) {
        List<String[]> result = new ArrayList<>();
        for (String t : parseListField(text)) {
            // 形如 ["initial->processing"] 或 "initial->processing"
            String[] kv = t.split("->");
            if (kv.length == 2) result.add(new String[]{kv[0].trim(), kv[1].trim()});
        }
        return result;
    }

    /** 探测本机 Ollama 是否在 11434 端口可用 */
    private boolean isOllamaUp() {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("http://localhost:11434/api/tags").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
