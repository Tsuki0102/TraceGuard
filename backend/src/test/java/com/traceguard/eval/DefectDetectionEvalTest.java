package com.traceguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.core.ConsistencyChecker;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Requirement;
import com.traceguard.service.ConsistencyJudge;
import com.traceguard.util.JavaCodeParserUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-007：缺陷检测质量评测（缺陷检测准确率 / 漏检率 / 误报率）
 *
 * AUD-02 统一口径（与 GAP-023 阈值标定同一真实算法链路，消除两报告矛盾）：
 *   - 用生产 ConsistencyChecker 对 GAP-007 三个样例工程实时计算分项得分与综合相似度
 *     （语义向量缺省走 TF-IDF+jieba+词典降级）；
 *   - 判定单元 = 已对齐的需求-代码对（requirementCode 非空 且 在当前工程找到对应代码单元）；
 *   - 规则判定：totalSimilarity &lt; t1 判缺陷，主类型按 GAP-020 四类口径匹配；
 *   - LLM 语义判定（AUD-02，可选）：配置 QWEN_API_KEY 后，对每个对齐对调用大模型
 *     做需求-代码一致性二审，LLM 判定结果覆盖规则判定（engine=llm，如实记录成功/失败数）；
 *     未配置密钥时降级 engine=rule。
 *   - 范围外对（requirementCode 为空）不进入四指标矩阵，其检出由 generateDefects 未匹配检测产出。
 *
 * 四指标口径（AUD-02 定稿：检出即 TP，主类型单列）：
 *   TP = 标注缺陷对 且 系统报缺陷（不论主类型）
 *   FP = 标注一致对 且 系统报缺陷
 *   FN = 标注缺陷对 且 系统未报
 *   TN = 标注一致对 且 系统未报
 *   缺陷检测准确率 = (TP+TN)/全部   漏检率 = FN/(TP+FN)   误报率 = FP/(FP+TN)
 *   主类型判定准确率 = 检出缺陷对中主类型与标注一致的比例（单列报告，不并入 TP）
 */
@DisplayName("GAP-007 缺陷检测质量评测（真实算法链路 + 可选 LLM 语义判定，AUD-02）")
class DefectDetectionEvalTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String[] SOURCES = {"ecommerce-order", "api-service", "exam-system"};

    /** 生产默认权重/阈值（application.yml analysis.default-*） */
    private static final double ALPHA = 0.4, BETA = 0.35, GAMMA = 0.25;
    private static final double T1 = 0.8, T2 = 0.5;

    /** 对齐的标注对快照 */
    private static final List<PairScore> pairScores = new ArrayList<>();
    /** 评测引擎与 LLM 调用统计 */
    private static String engine = "rule";
    private static int llmOk = 0, llmFail = 0;

    private static class PairScore {
        String id;
        String defectType;       // 标注主类型（一致对为空）
        boolean groundTruthDefective;
        double totalSimilarity;
        boolean ruleDetected;    // 规则判定（基线，始终保留用于回退）
        boolean detected;        // 系统判定（规则 or LLM）
        String detectedMainType; // 系统判定主类型（判定为一致时为空）
        String llmReason;        // LLM 判定依据（诊断用）
        boolean llmOverridden;   // 本次是否被 LLM 判定覆盖
    }

    @BeforeAll
    static void runScoring() throws Exception {
        ConsistencyJudge judge = LlmJudgeFactory.build();
        // AUD-02：优先读取 llm-verdicts.json（PowerShell 逐条调用产出，规避 Java 连续调用被 DashScope 节流），
        // 存在则以其作为 LLM 判定覆盖；否则回退 Java 侧 LlmJudgeFactory 直连。
        Map<String, JsonNode> verdictById = new HashMap<>();
        Path verdictFile = Paths.get("llm-verdicts.json");
        if (Files.exists(verdictFile)) {
            JsonNode verdictRoot = OM.readTree(Files.readString(verdictFile));
            verdictRoot.fields().forEachRemaining(e -> verdictById.put(e.getKey(), e.getValue()));
        }
        if (!verdictById.isEmpty()) {
            judge = null; // 已由外部逐条判定，不再走 Java 直连
            engine = "llm";
        }
        if (judge != null) {
            engine = "llm";
        }
        Path datasetDir = resolveDatasetDir();
        JsonNode labelRoot = OM.readTree(Files.readString(datasetDir.resolve("consistency-labels.json")));
        Map<String, JsonNode> labelById = new HashMap<>();
        labelRoot.path("pairs").forEach(p -> labelById.put(p.path("id").asText(), p));

        ConsistencyChecker checker = new ConsistencyChecker();
        long idSeq = 1;
        for (String source : SOURCES) {
            Path sourceDir = datasetDir.getParent().resolve(source);
            List<Requirement> requirements = parseRequirements(sourceDir.resolve("requirements.txt"), idSeq);
            idSeq += requirements.size();
            List<CodeUnit> codeUnits = parseCode(sourceDir, idSeq);
            idSeq += codeUnits.size();

            List<ConsistencyResult> results = checker.checkConsistency(
                    0L, 0L, requirements, codeUnits, ALPHA, BETA, GAMMA, T1, T2);

            Map<Long, Requirement> reqById = new HashMap<>();
            requirements.forEach(r -> reqById.put(r.getId(), r));
            Map<Long, CodeUnit> codeById = new HashMap<>();
            codeUnits.forEach(c -> codeById.put(c.getId(), c));

            Map<String, ConsistencyResult> index = new HashMap<>();
            for (ConsistencyResult r : results) {
                Requirement req = reqById.get(r.getRequirementId());
                CodeUnit code = codeById.get(r.getCodeUnitId());
                if (req == null || code == null) continue;
                index.put(req.getRequirementId() + "#" + code.getClassName() + "." + code.getMethodName(), r);
            }

            // 对齐标注对（仅 in-scope：requirementCode 非空）
            for (Map.Entry<String, JsonNode> e : labelById.entrySet()) {
                JsonNode p = e.getValue();
                if (!source.equals(p.path("source").asText())) continue;
                if ("exclude".equals(p.path("scope").asText(""))) continue; // AUD-02：仅含基础代码缺陷的对移出四类口径评测
                String reqCode = p.path("requirementCode").asText("");
                if (reqCode.isEmpty()) continue; // 范围外对不进入一致性矩阵
                String cls = p.path("codeFile").asText("").replaceFirst("\\.java$", "");
                String key = reqCode + "#" + cls + "." + p.path("method").asText();
                ConsistencyResult r = index.get(key);
                if (r == null) continue; // 标注对在当前工程未找到对应代码单元
                Requirement req = reqById.get(r.getRequirementId());
                CodeUnit code = codeById.get(r.getCodeUnitId());
                PairScore ps = new PairScore();
                ps.id = e.getKey();
                ps.defectType = p.path("defectType").asText("");
                ps.groundTruthDefective = "defective".equals(p.path("label").asText());
                ps.totalSimilarity = r.getTotalSimilarity();
                // 规则判定（基线，始终保留用于回退）
                ps.ruleDetected = ps.totalSimilarity < T1;
                ps.detected = ps.ruleDetected;
                ps.detectedMainType = r.getDefectType() == null ? "" : r.getDefectType();
                // LLM 语义判定二审（可选）：LLM 判定覆盖规则判定
                // 优先取外部 llm-verdicts.json；缺失时回退 Java 直连（LlmJudgeFactory）
                JsonNode extVerdict = verdictById.get(ps.id);
                if (extVerdict != null) {
                    // 置信度门槛：consistent 必须显式为布尔；判不一致时要求 defectType 非空，
                    // 否则视为无效判定（不覆盖、计失败），与 ConsistencyJudge.parse 口径一致。
                    boolean consistent = extVerdict.path("consistent").isBoolean()
                            ? extVerdict.path("consistent").asBoolean() : false;
                    boolean hasType = !extVerdict.path("defectType").asText("").isEmpty();
                    if (extVerdict.path("consistent").isBoolean() && (consistent || hasType)) {
                        llmOk++;
                        ps.detected = !consistent;
                        ps.detectedMainType = consistent ? "" : extVerdict.path("defectType").asText("");
                        ps.llmReason = extVerdict.path("reason").asText("");
                        ps.llmOverridden = true;
                    } else {
                        llmFail++;
                    }
                } else if (judge != null && req != null && code != null) {
                    ConsistencyJudge.Judgement j = judge.judge(
                            req.getOriginalText(), code.getCodeContent(),
                            r.getSemanticSimilarity(), r.getConstraintMatchDegree(),
                            r.getInvariantSatisfaction(), r.getTotalSimilarity(), r.getDefectType());
                    if (j != null) {
                        llmOk++;
                        ps.detected = !j.isConsistent();
                        ps.detectedMainType = j.isConsistent() ? "" : j.getDefectType();
                        ps.llmReason = j.getReason();
                        ps.llmOverridden = true;
                    } else {
                        llmFail++;
                    }
                }
                pairScores.add(ps);
            }
        }

        // AUD-02 评测口径修复：LLM 成功率过低时整体回退规则基线，
        // 避免把少数（如 3/55）成功调用混入「LLM 引擎」结论造成误导。
        // 置信度门槛：仅当 LLM 显式给出布尔 consistent 判定（见 ConsistencyJudge.parse，
        // 缺失 consistent 或判不一致但无类型均视为无效）才允许覆盖；此处再叠加整体成功率门槛。
        int llmTotal = llmOk + llmFail;
        double llmSuccessRate = llmTotal == 0 ? 0.0 : (double) llmOk / llmTotal;
        final double LLM_MIN_SUCCESS_RATE = 0.10; // 成功率低于 10% 视为 LLM 不可用
        if ("llm".equals(engine) && llmSuccessRate < LLM_MIN_SUCCESS_RATE) {
            int reverted = 0;
            for (PairScore ps : pairScores) {
                if (ps.llmOverridden) {
                    ps.detected = ps.ruleDetected;          // 还原规则判定
                    ps.detectedMainType = ps.ruleDetected ? ps.detectedMainType : "";
                    ps.llmReason = "(LLM 成功率过低已回退规则基线) " + (ps.llmReason == null ? "" : ps.llmReason);
                    ps.llmOverridden = false;
                    reverted++;
                }
            }
            engine = "rule (LLM成功率过低回退)";
            System.out.println("[GAP-007] LLM 成功率 " + llmOk + "/" + llmTotal + " = "
                    + String.format("%.1f", llmSuccessRate * 100) + "% < "
                    + String.format("%.0f", LLM_MIN_SUCCESS_RATE * 100)
                    + "%，已整体回退规则基线（还原 " + reverted + " 对覆盖）");
        }
        assertFalse(pairScores.isEmpty(), "对齐标注对为空：无法评测");
    }

    @Test
    @DisplayName("缺陷检测四指标（真实算法链路 + 可选 LLM）")
    void detectionMetrics() throws Exception {
        long tp = 0, fp = 0, fn = 0, tn = 0;
        Map<String, long[]> byType = new HashMap<>(); // 主类型 -> [TP, FN]
        List<PairScore> typeMismatch = new ArrayList<>(); // 已检出但主类型与标注不一致（AUD-02 定稿：单列不计入漏检）

        for (PairScore ps : pairScores) {
            if (ps.groundTruthDefective) {
                boolean typeMatched = !ps.defectType.isEmpty() && ps.defectType.equals(ps.detectedMainType);
                if (ps.detected) {
                    tp++;
                    byType.computeIfAbsent(ps.defectType, k -> new long[2])[0]++;
                    if (!typeMatched) {
                        typeMismatch.add(ps);
                    }
                } else {
                    fn++;
                    byType.computeIfAbsent(ps.defectType, k -> new long[2])[1]++;
                }
            } else {
                if (ps.detected) fp++;
                else tn++;
            }
        }

        double accuracy = EvalMetrics.defectAccuracy(tp, tn, fp, fn);
        double miss = EvalMetrics.missRate(tp, fn);
        double fpr = EvalMetrics.falsePositiveRate(fp, tn);
        // 主类型判定准确率：检出缺陷对中类型与标注一致的比例（单列，不并入 TP/漏检）
        double typeAcc = tp == 0 ? 1.0 : (double) (tp - typeMismatch.size()) / tp;

        // FUN-01：无论当前引擎如何，同时保留规则基线四指标（ruleDetected 始终记录），LLM 模式时并列输出对比
        long ruleTp = 0, ruleFp = 0, ruleFn = 0, ruleTn = 0;
        for (PairScore ps : pairScores) {
            if (ps.groundTruthDefective) {
                if (ps.ruleDetected) ruleTp++;
                else ruleFn++;
            } else {
                if (ps.ruleDetected) ruleFp++;
                else ruleTn++;
            }
        }
        double ruleAccuracy = EvalMetrics.defectAccuracy(ruleTp, ruleTn, ruleFp, ruleFn);
        double ruleMiss = EvalMetrics.missRate(ruleTp, ruleFn);
        double ruleFpr = EvalMetrics.falsePositiveRate(ruleFp, ruleTn);

        StringBuilder sb = new StringBuilder();
        String engineTitle;
        if ("llm".equals(engine)) {
            engineTitle = "真实算法链路 + LLM 语义判定（可用）";
        } else if (engine.startsWith("rule (LLM")) {
            engineTitle = "真实算法链路 + LLM 判定不可用（已回退规则基线）";
        } else {
            engineTitle = "真实算法链路（engine=rule，未配置 LLM）";
        }
        sb.append(EvalReportWriter.envSnapshot(engineTitle, "N/A", "关闭"));
        sb.append("\n| 指标 | 值 | 目标 | 判定 |\n|---|---|---|---|\n");
        sb.append("| 缺陷检测准确率 | ").append(EvalReportWriter.pct(accuracy)).append(" | >= 80% | ")
          .append(accuracy >= 0.80 ? "达标" : "未达标").append(" |\n");
        sb.append("| 漏检率 | ").append(EvalReportWriter.pct(miss)).append(" | <= 15% | ")
          .append(miss <= 0.15 ? "达标" : "未达标").append(" |\n");
        sb.append("| 误报率 | ").append(EvalReportWriter.pct(fpr)).append(" | <= 10% | ")
          .append(fpr <= 0.10 ? "达标" : "未达标").append(" |\n");
        sb.append("| 主类型判定准确率 | ").append(EvalReportWriter.pct(typeAcc)).append(" | - | ")
          .append("检出缺陷中主类型与标注一致（单列，不并入 TP/漏检）").append(" |\n");
        sb.append("| 对齐标注对 M | ").append(pairScores.size()).append(" | 40~100 | ")
          .append(pairScores.size() >= 40 ? "达标" : "偏少").append(" |\n");
        sb.append("| 判定引擎 | ").append(engine).append(" | - | ").append("llm".equals(engine)
          ? "LLM 判定成功 " + llmOk + "/" + (llmOk + llmFail) + " 对"
          : (engine.startsWith("rule (LLM")
             ? "LLM 判定 " + llmOk + "/" + (llmOk + llmFail) + " 成功率过低已回退规则基线"
             : "规则判定（LLM 未启用）")).append(" |\n");
        sb.append("| LLM 成功率 | ").append(EvalReportWriter.pct((llmOk + llmFail) == 0 ? 0 : (double) llmOk / (llmOk + llmFail)))
          .append(" | >= 10% | ").append((llmOk + llmFail) == 0 ? "未启用"
          : ((double) llmOk / (llmOk + llmFail) < 0.10 ? "不足，已回退规则基线" : "达标")).append(" |\n");
        sb.append("| 规则阈值 t1 | ").append(String.format("%.2f", T1)).append(" | 生产默认 | 见阈值标定报告联动 |\n");
        // FUN-01：LLM 模式下并列呈现规则基线，明确主验收口径（报告不再只呈现规则模式单一数据）
        if ("llm".equals(engine)) {
            sb.append("\n### 规则基线（并列对比，FUN-01）\n\n");
            sb.append("| 指标 | 规则模式（t1=").append(String.format("%.2f", T1)).append("） | LLM 语义判定 | 目标 |\n|---|---|---|---|\n");
            sb.append("| 缺陷检测准确率 | ").append(EvalReportWriter.pct(ruleAccuracy))
              .append(" | ").append(EvalReportWriter.pct(accuracy)).append(" | >= 80% |\n");
            sb.append("| 漏检率 | ").append(EvalReportWriter.pct(ruleMiss))
              .append(" | ").append(EvalReportWriter.pct(miss)).append(" | <= 15% |\n");
            sb.append("| 误报率 | ").append(EvalReportWriter.pct(ruleFpr))
              .append(" | ").append(EvalReportWriter.pct(fpr)).append(" | <= 10% |\n");
            sb.append("| 混淆矩阵 | TP=").append(ruleTp).append(" FP=").append(ruleFp)
              .append(" FN=").append(ruleFn).append(" TN=").append(ruleTn)
              .append(" | TP=").append(tp).append(" FP=").append(fp)
              .append(" FN=").append(fn).append(" TN=").append(tn).append(" | - |\n");
            sb.append("\n> **主验收口径（FUN-01）**：以 LLM 语义判定为最终判定（覆盖规则判定）；规则模式为基线并列呈现，"
                    + "两者共用同一真实算法链路与标注集，避免报告仅呈现规则模式造成验收口径误读。\n");
        }
        sb.append("\n混淆矩阵：TP=").append(tp).append("，FP=").append(fp)
          .append("，FN=").append(fn).append("，TN=").append(tn).append("\n\n");
        sb.append("\n### 各主类型检出情况（TP/FN）\n\n| 主类型 | TP | FN |\n|---|---|---|\n");
        byType.forEach((t, arr) -> sb.append("| ").append(t).append(" | ").append(arr[0]).append(" | ").append(arr[1]).append(" |\n"));
        sb.append("\n### 误报（FP）与漏检（FN）明细（AUD-02 诊断）\n\n");
        sb.append("| 类型 | 标注对 | 标注 | 系统判定类型 | LLM 依据 |\n|---|---|---|---|---|\n");
        for (PairScore ps : pairScores) {
            boolean isFp = !ps.groundTruthDefective && ps.detected;
            boolean isFn = ps.groundTruthDefective && !ps.detected;
            if (isFp || isFn) {
                sb.append("| ").append(isFp ? "FP" : "FN").append(" | ").append(ps.id)
                  .append(" | ").append(ps.groundTruthDefective ? "defective/" + ps.defectType : "consistent")
                  .append(" | ").append(ps.detectedMainType.isEmpty() ? "-" : ps.detectedMainType)
                  .append(" | ").append(ps.llmReason == null ? "-" : ps.llmReason.replace('|', '丨')).append(" |\n");
            }
        }
        sb.append("\n### 主类型判定偏差（AUD-02 定稿：已检出但归类不同，单列不计入漏检）\n\n");
        sb.append("| 标注对 | 标注主类型 | 系统判定主类型 | LLM 依据 |\n|---|---|---|---|\n");
        for (PairScore ps : typeMismatch) {
            sb.append("| ").append(ps.id).append(" | ").append(ps.defectType)
              .append(" | ").append(ps.detectedMainType.isEmpty() ? "-" : ps.detectedMainType)
              .append(" | ").append(ps.llmReason == null ? "-" : ps.llmReason.replace('|', '丨')).append(" |\n");
        }
        sb.append("\n### 口径与差距分析\n\n");
        sb.append("- **口径（AUD-02）**：与 GAP-023 阈值标定同一真实算法链路（生产 ConsistencyChecker + "
          + "GAP-005 约束证据检测）；范围外对（需求缺失/代码超范围实现）由 generateDefects 未匹配检测产出，不进入本矩阵。\n");
        sb.append("- **四指标定稿（AUD-02）**：TP 按「检出缺陷即 TP」计（不论主类型），主类型判定准确率单列报告，"
          + "避免将分类偏差计入漏检；漏检率 = 标注缺陷且系统未报的比例。\n");
        sb.append("- **LLM 语义判定（AUD-02 + 评测口径修复）**：配置 QWEN_API_KEY 或本地 CodeLlama 后，每个对齐对由大模型"
          + "做需求-代码一致性二审，判定结果覆盖规则判定；未配置时 engine=rule。**置信度门槛**：LLM 响应须显式给出"
          + "布尔 `consistent`，且判不一致时必须带四类主类型之一，否则视为无效判定（不覆盖、计失败），与 ConsistencyJudge.parse 口径一致。"
          + "**整体成功率门槛**：当 LLM 有效成功率 < 10%（如本地小模型不遵循 JSON 指令导致大面积解析失败），评测**整体回退规则基线**"
          + "并将引擎标注为「rule (LLM成功率过低回退)」，避免把极少数成功调用混入「LLM 引擎」结论造成误导（本次 CodeLlama 7B 即属此情形）。\n");
        sb.append("- 漏检率高于目标时：优先提升约束/不变量维度的证据匹配，并核对未检出的标注对。\n");
        sb.append("- 误报率高于目标时：收紧阈值 T1/T2，或确认 LLM 判定未被异常成功率稀释（见成功率门槛）。\n");
        EvalReportWriter.writeSection("defect-detection", "二、缺陷检测评测（四指标）", sb.toString());

        System.out.println("[GAP-007 defect] 对齐对=" + pairScores.size()
                + "，engine=" + engine + "（LLM 成功 " + llmOk + "/失败 " + llmFail + "）"
                + "，准确率=" + EvalReportWriter.pct(accuracy)
                + "，漏检率=" + EvalReportWriter.pct(miss) + "，误报率=" + EvalReportWriter.pct(fpr)
                + "（TP=" + tp + " FP=" + fp + " FN=" + fn + " TN=" + tn + "）");

        assertTrue(tp + tn + fp + fn == pairScores.size(), "四格计数之和应等于对齐标注样本总数");
        assertTrue(accuracy >= 0 && accuracy <= 1 && miss >= 0 && miss <= 1 && fpr >= 0 && fpr <= 1,
                "各指标应为合法概率值");
    }

    // ==================== 数据加载（与 ThresholdCalibrationEvalTest 同构） ====================

    /** 解析 requirements.txt（兼容 "REQ-XXX: 文本" 与 "REQ-XXX | 标题 | ..." 两种格式） */
    private static List<Requirement> parseRequirements(Path reqFile, long startId) throws Exception {
        List<Requirement> list = new ArrayList<>();
        if (!Files.exists(reqFile)) return list;
        long id = startId;
        for (String line : Files.readAllLines(reqFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String code;
            int colon = t.indexOf(':');
            int pipe = t.indexOf('|');
            if (pipe >= 0 && (colon < 0 || pipe < colon)) {
                code = t.substring(0, pipe).trim();
            } else if (colon > 0) {
                code = t.substring(0, colon).trim();
            } else {
                continue;
            }
            if (!code.startsWith("REQ-")) continue;
            String text = t.substring(code.length()).replaceFirst("^[:|]\\s*", "").trim();
            Requirement req = new Requirement();
            req.setId(id++);
            req.setRequirementId(code);
            req.setOriginalText(text);
            list.add(req);
        }
        return list;
    }

    /** 用生产 JavaCodeParserUtil 解析样例工程代码（AST 级，Soot 缺省自动降级） */
    private static List<CodeUnit> parseCode(Path sourceDir, long startId) {
        Path codeDir = sourceDir.resolve("code");
        List<CodeUnit> units = new JavaCodeParserUtil().parseProject(codeDir.toString());
        long id = startId;
        for (CodeUnit u : units) {
            u.setId(id++);
        }
        return units;
    }

    /** 定位 samples/dataset 目录（测试运行目录为 backend/） */
    private static Path resolveDatasetDir() {
        String userDir = System.getProperty("user.dir", ".");
        String[] candidates = {
                Paths.get(userDir, "samples", "dataset").toString(),
                Paths.get(userDir, "..", "samples", "dataset").toString(),
                Paths.get(userDir, "..", "..", "samples", "dataset").toString(),
        };
        for (String c : candidates) {
            if (new File(c, "consistency-labels.json").isFile()) {
                return Paths.get(c);
            }
        }
        throw new IllegalStateException("未找到 samples/dataset/consistency-labels.json（候选: " + String.join(", ", candidates) + "）");
    }
}
