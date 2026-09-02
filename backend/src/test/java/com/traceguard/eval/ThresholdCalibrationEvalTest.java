package com.traceguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.LlmProperties;
import com.traceguard.core.ConsistencyChecker;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import com.traceguard.service.impl.LocalBgeEmbeddingClient;
import com.traceguard.util.CodeDefectPatternDetector;
import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.SemanticVectorUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GAP-023：约束/不变量维度阈值标定（用 GAP-007 数据集跑分，标定一致性判定阈值并留档）。
 *
 * 标定方法（设计方案 4.8.6 步骤 4）：
 *   1. 加载 GAP-007 三个样例工程（ecommerce-order / api-service / exam-system）的需求与代码；
 *   2. 用生产链路 ConsistencyChecker 对每对 (Ri, Cj) 计算真实分项得分与综合相似度；
 *   3. 将 consistency-labels.json 的标注对与计算结果按 (requirementCode, 类名.方法名) 对齐，
 *      得到每个标注对的综合相似度与缺陷主类型；
 *   4. 扫描一致性阈值 t1（缺陷判定门限：totalSimilarity < t1 判定为缺陷），
 *      按 GAP-007 四指标口径计算准确率/漏检率/误报率，选取最优 t1；
 *   5. 输出 docs/03-报告/阈值标定报告.md（含推荐阈值、扫描明细、分项得分分布），作为留档。
 *
 * 说明：
 *   - 范围外对（requirementCode 为空，属"需求缺失/代码超范围实现"）不进入一致性矩阵标定，
 *     该类缺陷由 generateDefects 的未匹配检测产出，不受 t1 影响。
 *   - 权重 alpha/beta/gamma 固定为 GAP-046 约束主导标定参数（0.2/0.55/0.25）——
 *     注意：与生产默认权重（application.yml analysis.default-weight-*，0.4/0.35/0.25）不同，
 *     标定结论与生产配置的对应关系见《阈值标定报告》结论节。t2 仅影响"一般/严重"分级，
 *     不改变是否报缺陷，故标定聚焦 t1；t2 随 t1 一并记录推荐值。
 *   - 本测试不依赖 Spring 容器与数据库，直接 new ConsistencyChecker / JavaCodeParserUtil，
 *     语义向量缺省时自动走 TF-IDF + jieba + 词典降级路径（与无 LLM/形式化规约的生产默认一致）。
 */
@DisplayName("GAP-023 阈值标定（GAP-007 数据集）")
class ThresholdCalibrationEvalTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String[] SOURCES = {"ecommerce-order", "api-service", "exam-system"};

    /** 标定链路权重（GAP-046 约束主导参数 α=0.2/β=0.55/γ=0.25；与生产默认 0.4/0.35/0.25 不同，见 application.yml） */
    private static final double ALPHA = 0.2, BETA = 0.55, GAMMA = 0.25;

    /** 标注对 -> 计算得分快照 */
    private static final List<PairScore> pairScores = new ArrayList<>();

    /** FUN-04b：源工程 -> 类名 -> 同类方法签名列表（LLM 判定的分工上下文） */
    private static final Map<String, Map<String, List<String>>> classContexts = new HashMap<>();

    /** 收集每个类的公开方法签名（供 LLM 判定“其他方法分工”证据），每条截断至 120 字符 */
    private static void collectClassContext(String source, List<CodeUnit> codeUnits) {
        Map<String, List<String>> byClass = classContexts.computeIfAbsent(source, k -> new HashMap<>());
        for (CodeUnit u : codeUnits) {
            if (u.getClassName() == null || u.getMethodName() == null || "<init>".equals(u.getMethodName())) {
                continue;
            }
            String sig = extractFirstLine(u.getCodeContent());
            if (sig.isEmpty()) continue;
            byClass.computeIfAbsent(u.getClassName(), k -> new ArrayList<>()).add(sig);
        }
    }

    /** 取代码首行非注解非空行（通常是方法签名） */
    private static String extractFirstLine(String content) {
        if (content == null) return "";
        for (String raw : content.split("\n")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("/") || t.startsWith("*") || t.startsWith("//")) continue;
            return t.length() > 120 ? t.substring(0, 120) : t;
        }
        return "";
    }

    /** FUN-04①：本次标定是否启用本地 BGE 语义向量 */
    private static volatile boolean bgeEnabled = false;

    private static class PairScore {
        String id;
        String source;
        String className;       // FUN-04b：所属类名（分工上下文检索键）
        String defectType;      // 标注主类型（consistent 对为空）
        boolean groundTruthDefective;
        double totalSimilarity;
        double semanticSimilarity;
        double constraintMatch;
        double invariantSatisfaction;
        String detectedMainType; // checker 判定的主类型
        String reqText;          // 临时调试用
        String codeText;         // 临时调试用
        double defectRisk;       // GAP-046 代码缺陷模式风险分（临时调试用）
    }

    @BeforeAll
    static void runScoring() throws Exception {
        System.setProperty("gap046.debug", "true");
        Path datasetDir = resolveDatasetDir();
        JsonNode labelRoot = OM.readTree(Files.readString(datasetDir.resolve("consistency-labels.json")));
        Map<String, JsonNode> labelById = new HashMap<>();
        labelRoot.path("pairs").forEach(p -> labelById.put(p.path("id").asText(), p));

        ConsistencyChecker checker = new ConsistencyChecker();
        // FUN-04①：默认启用本地 BGE Embedding 恢复语义维度区分度（EMBEDDING_MODEL_PATH 已注入时走 BGE，否则 TF-IDF 降级）
        LocalBgeEmbeddingClient bge = buildLocalBgeClient();
        long idSeq = 1;
        for (String source : SOURCES) {
            Path sourceDir = datasetDir.getParent().resolve(source);
            List<Requirement> requirements = parseRequirements(sourceDir.resolve("requirements.txt"), idSeq);
            idSeq += requirements.size();
            List<CodeUnit> codeUnits = parseCode(sourceDir, idSeq);
            idSeq += codeUnits.size();
            if (bge != null && bge.available()) {
                applyBgeSemanticVectors(bge, requirements, codeUnits);
            }
            collectClassContext(source, codeUnits); // FUN-04b：同类方法分工上下文（LLM 判定证据）

            // 生产链路计算（t1/t2 仅用于状态标注，得分与主类型不依赖其取值，这里用默认值）
            // FUN-04b：类级证据（字段/常量声明）供量化边界核对信号使用
            Map<String, List<String>> classEvidence =
                    com.traceguard.util.ClassEvidenceScanner.scanConstants(sourceDir.resolve("code").toString());
            List<ConsistencyResult> results = checker.checkConsistency(
                    0L, 0L, requirements, codeUnits, null, classEvidence, ALPHA, BETA, GAMMA, 0.8, 0.5);

            Map<Long, Requirement> reqById = new HashMap<>();
            requirements.forEach(r -> reqById.put(r.getId(), r));
            Map<Long, CodeUnit> codeById = new HashMap<>();
            codeUnits.forEach(c -> codeById.put(c.getId(), c));

            // 结果按 (requirementCode, 类名.方法名) 建索引
            Map<String, ConsistencyResult> index = new HashMap<>();
            for (ConsistencyResult r : results) {
                Requirement req = reqById.get(r.getRequirementId());
                CodeUnit code = codeById.get(r.getCodeUnitId());
                if (req == null || code == null) continue;
                index.put(req.getRequirementId() + "#" + code.getClassName() + "." + code.getMethodName(), r);
            }

            // 对齐标注对
            for (Map.Entry<String, JsonNode> e : labelById.entrySet()) {
                JsonNode p = e.getValue();
                if (!source.equals(p.path("source").asText())) continue;
                if ("exclude".equals(p.path("scope").asText(""))) continue; // AUD-02：仅含基础代码缺陷的对移出四类口径评测
                String reqCode = p.path("requirementCode").asText("");
                if (reqCode.isEmpty()) continue; // 范围外对不参与一致性矩阵标定
                String cls = p.path("codeFile").asText("").replaceFirst("\\.java$", "");
                String key = reqCode + "#" + cls + "." + p.path("method").asText();
                ConsistencyResult r = index.get(key);
                if (r == null) continue; // 标注对在当前工程未找到对应代码单元
                PairScore ps = new PairScore();
                ps.id = e.getKey();
                ps.source = source;
                ps.className = cls;
                ps.defectType = p.path("defectType").asText("");
                ps.groundTruthDefective = "defective".equals(p.path("label").asText());
                ps.totalSimilarity = r.getTotalSimilarity();
                ps.semanticSimilarity = r.getSemanticSimilarity();
                ps.constraintMatch = r.getConstraintMatchDegree();
                ps.invariantSatisfaction = r.getInvariantSatisfaction();
                ps.detectedMainType = r.getDefectType() == null ? "" : r.getDefectType();
                Requirement reqObj = reqById.get(r.getRequirementId());
                CodeUnit codeObj = codeById.get(r.getCodeUnitId());
                ps.reqText = reqObj.getOriginalText();
                ps.codeText = codeObj.getCodeContent();
                ps.defectRisk = CodeDefectPatternDetector.detectDefectRisk(ps.reqText, ps.codeText);
                pairScores.add(ps);
            }
        }
        assertFalse(pairScores.isEmpty(), "标定数据集为空：未能将标注对与计算结果对齐");

        // 离线实验台（FUN-04b）：-Dgap046.dump=<path> 时导出标注对快照（需求原文/代码/得分/真值），
        // 供 LLM 判定提示词离线调优使用，不改变标定逻辑本身。
        String dumpPath = System.getProperty("gap046.dump");
        if (dumpPath != null && !dumpPath.trim().isEmpty()) {
            StringBuilder json = new StringBuilder("{\n  \"alpha\": ").append(ALPHA)
                    .append(", \"beta\": ").append(BETA).append(", \"gamma\": ").append(GAMMA).append(",\n");
            // FUN-04b：同类方法分工上下文（source -> class -> [签名...]）
            json.append("  \"classes\": {\n");
            int ci = 0;
            for (Map.Entry<String, Map<String, List<String>>> se : classContexts.entrySet()) {
                json.append("    \"").append(se.getKey()).append("\": {");
                int cj = 0;
                for (Map.Entry<String, List<String>> ce : se.getValue().entrySet()) {
                    if (cj++ > 0) json.append(", ");
                    json.append('"').append(ce.getKey()).append("\":").append(om().writeValueAsString(ce.getValue()));
                }
                json.append("}").append(++ci < classContexts.size() ? "," : "").append('\n');
            }
            json.append("  },\n  \"pairs\": [\n");
            for (int i = 0; i < pairScores.size(); i++) {
                PairScore ps = pairScores.get(i);
                json.append("    {\"id\":\"").append(ps.id).append('"')
                        .append(",\"source\":\"").append(ps.source).append('"')
                        .append(",\"class\":\"").append(ps.className == null ? "" : ps.className).append('"')
                        .append(",\"label\":\"").append(ps.groundTruthDefective ? "defective" : "consistent").append('"')
                        .append(",\"defectType\":\"").append(ps.defectType == null ? "" : ps.defectType).append('"')
                        .append(",\"total\":").append(ps.totalSimilarity)
                        .append(",\"sem\":").append(ps.semanticSimilarity)
                        .append(",\"con\":").append(ps.constraintMatch)
                        .append(",\"inv\":").append(ps.invariantSatisfaction)
                        .append(",\"risk\":").append(ps.defectRisk).append(',')
                        .append("\"req\":").append(om().writeValueAsString(ps.reqText)).append(',')
                        .append("\"code\":").append(om().writeValueAsString(ps.codeText)).append('}')
                        .append(i < pairScores.size() - 1 ? "," : "").append('\n');
            }
            json.append("  ]\n}\n");
            Files.write(Paths.get(dumpPath), json.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("[GAP-046-DUMP] 已导出标注对快照 -> " + dumpPath + "（" + pairScores.size() + " 对）");
        }
    }

    private static ObjectMapper om() {
        return OM;
    }

    @Test
    @DisplayName("扫描 t1 阈值并按 GAP-007 四指标标定")
    void calibrateThreshold() throws Exception {
        // 临时诊断：打印每个标注对的分项得分与 truth
        System.out.println("\n[GAP-046-DIAG] 标注对详细得分（id | source | truth | total | sem | con | inv | risk | type | detectedType）");
        for (PairScore ps : pairScores) {
            System.out.println(String.format("[GAP-046-DIAG] %s | %s | %s | %.3f | %.3f | %.3f | %.3f | %.3f | %s | %s",
                    ps.id, ps.source, ps.groundTruthDefective ? "DEFECT" : "OK",
                    ps.totalSimilarity, ps.semanticSimilarity, ps.constraintMatch, ps.invariantSatisfaction,
                    ps.defectRisk, ps.defectType, ps.detectedMainType));
            if (("CL-004".equals(ps.id) || "CL-006".equals(ps.id) || "CL-013".equals(ps.id) || "CL-026".equals(ps.id)
                    || "CL-041".equals(ps.id) || "CL-044".equals(ps.id) || "CL-048".equals(ps.id) || "CL-049".equals(ps.id)
                    || "CL-060".equals(ps.id) || "CL-055".equals(ps.id) || "CL-042".equals(ps.id) || "CL-054".equals(ps.id))) {
                System.out.println("[GAP-046-DETAIL] " + ps.id + " REQ=" + ps.reqText);
                String compactCode = ps.codeText.replaceAll("\\s+", " ");
                System.out.println("[GAP-046-DETAIL] " + ps.id + " CODE=" + compactCode.substring(0, Math.min(300, compactCode.length())));
            }
        }
        // 扫描 t1：[0.40, 0.90] 步长 0.02
        List<double[]> sweep = new ArrayList<>(); // {t1, accuracy, miss, fpr, tp, fp, fn, tn}
        double bestT1 = 0.8, bestAcc = -1, bestMiss = 1, bestFpr = 1;
        for (int ti = 40; ti <= 90; ti += 2) {
            double t1 = ti / 100.0;
            long tp = 0, fp = 0, fn = 0, tn = 0;
            for (PairScore ps : pairScores) {
                // AUD-02 定稿口径：检出即 TP（主类型单列，不并入 TP/漏检），与 DefectDetectionEvalTest 一致
                boolean detected = ps.totalSimilarity < t1;
                if (ps.groundTruthDefective) {
                    if (detected) tp++;
                    else fn++;
                } else {
                    if (detected) fp++;
                    else tn++;
                }
            }
            double acc = EvalMetrics.defectAccuracy(tp, tn, fp, fn);
            double miss = EvalMetrics.missRate(tp, fn);
            double fpr = EvalMetrics.falsePositiveRate(fp, tn);
            sweep.add(new double[]{t1, acc, miss, fpr, tp, fp, fn, tn});
            // 择优：准确率最高；并列时漏检+误报更低者优先
            if (acc > bestAcc || (acc == bestAcc && (miss + fpr) < (bestMiss + bestFpr))) {
                bestAcc = acc; bestMiss = miss; bestFpr = fpr; bestT1 = t1;
            }
        }

        // 推荐 t2：取 t1 与最低缺陷相似度之间的中点，保证"一般/严重"分级有区分度
        double minDefectSim = pairScores.stream().filter(p -> p.groundTruthDefective)
                .mapToDouble(p -> p.totalSimilarity).min().orElse(bestT1 - 0.2);
        double recommendedT2 = Math.max(0.1, Math.min(bestT1 - 0.05, (bestT1 + minDefectSim) / 2.0));

        writeCalibrationReport(sweep, bestT1, recommendedT2, bestAcc, bestMiss, bestFpr);

        System.out.println("[GAP-023] 标定完成：对齐标注对=" + pairScores.size()
                + "，推荐 t1=" + bestT1 + "，准确率=" + EvalReportWriter.pct(bestAcc)
                + "，漏检率=" + EvalReportWriter.pct(bestMiss)
                + "，误报率=" + EvalReportWriter.pct(bestFpr));

        assertTrue(bestAcc >= 0 && bestAcc <= 1, "准确率应为合法概率值");
        assertTrue(bestT1 > 0 && bestT1 < 1, "推荐 t1 应位于 (0,1)");
        // P1-4 规则链路基线门禁（防回归）：历史最优 61.8%（2026-08-27）→ P1-4 去噪后 63.6%（2026-09-02）。
        // 若某改动使最优 t1 准确率跌破 60% 或误报率突破 35%，说明规则链路发生明显退化，须排查后再提交。
        assertTrue(bestAcc >= 0.60,
                "规则链路准确率回归门禁未通过：bestAcc=" + EvalReportWriter.pct(bestAcc) + " < 60%（基线 63.6%，2026-09-02）");
        assertTrue(bestFpr <= 0.35,
                "规则链路误报率回归门禁未通过：bestFpr=" + EvalReportWriter.pct(bestFpr) + " > 35%（基线 24.1%，2026-09-02）");
    }

    /**
     * P0-5 语义校准消融（离线仿真，-Dsem.ablation=true 启用）。
     * 原理：adjustedSim = totalSim*(1 - risk*0.55)，totalSim = α·sem + β·con + γ·inv。
     * 若仅替换语义映射 sem→f(sem)，则
     *   adjusted' = adjusted + α·(f(sem)-sem)·(1 - risk·0.55)（其余维度不变）。
     * 因 pairScores 已含 adjusted/risk/sem，可在不改链路的前提下精确仿真不同语义映射的标定结果。
     * 基线（f=identity）应复现 61.8% 以验证仿真口径。
     */
    @Test
    @EnabledIfSystemProperty(named = "sem.ablation", matches = "true")
    @DisplayName("P0-5 语义映射消融（sigmoid 温度缩放 / min-max）")
    void semanticAblation() {
        final double ALPHA = 0.2;
        final double RISK_WEIGHT = 0.55;
        // 语义变体（对 pairScores.semanticSimilarity 的映射）
        Map<String, java.util.function.DoubleUnaryOperator> variants = new LinkedHashMap<>();
        variants.put("linear(基线)", x -> x);
        variants.put("sigmoid(c=0.85,w=0.02)", x -> sigmoidVal(x, 0.85, 0.02));
        variants.put("sigmoid(c=0.85,w=0.05)", x -> sigmoidVal(x, 0.85, 0.05));
        variants.put("sigmoid(c=0.87,w=0.02)", x -> sigmoidVal(x, 0.87, 0.02));
        variants.put("sigmoid(c=0.80,w=0.05)", x -> sigmoidVal(x, 0.80, 0.05));
        variants.put("sigmoid(c=0.90,w=0.03)", x -> sigmoidVal(x, 0.90, 0.03));
        variants.put("minmax-batch(线性归一)", x -> Double.NaN); // 需批内数组，离线不可精确仿真，占位标记

        System.out.println("\n[P0-5-ABLATION] 语义映射消融（M=" + pairScores.size() + "，扫描 t1∈[0.40,0.90]）");
        System.out.println("[P0-5-ABLATION] variant | bestT1 | accuracy | miss | fpr | TP/FP/FN/TN");
        for (Map.Entry<String, java.util.function.DoubleUnaryOperator> e : variants.entrySet()) {
            double bestT1 = 0.8, bestAcc = -1, bestMiss = 1, bestFpr = 1;
            long btp = 0, bfp = 0, bfn = 0, btn = 0;
            for (int ti = 40; ti <= 90; ti += 2) {
                double t1 = ti / 100.0;
                long tp = 0, fp = 0, fn = 0, tn = 0;
                for (PairScore ps : pairScores) {
                    double sem = ps.semanticSimilarity;
                    double newSem = e.getValue().applyAsDouble(sem);
                    double adj;
                    if (Double.isNaN(newSem)) {
                        adj = ps.totalSimilarity; // 不可仿真变体按基线
                    } else {
                        adj = ps.totalSimilarity + ALPHA * (newSem - sem) * (1.0 - ps.defectRisk * RISK_WEIGHT);
                    }
                    boolean detected = adj < t1;
                    if (ps.groundTruthDefective) {
                        if (detected) tp++; else fn++;
                    } else {
                        if (detected) fp++; else tn++;
                    }
                }
                double acc = EvalMetrics.defectAccuracy(tp, tn, fp, fn);
                double miss = EvalMetrics.missRate(tp, fn);
                double fpr = EvalMetrics.falsePositiveRate(fp, tn);
                if (acc > bestAcc || (acc == bestAcc && (miss + fpr) < (bestMiss + bestFpr))) {
                    bestAcc = acc; bestMiss = miss; bestFpr = fpr; bestT1 = t1;
                    btp = tp; bfp = fp; bfn = fn; btn = tn;
                }
            }
            System.out.println(String.format("[P0-5-ABLATION] %-28s | %.2f | %s | %s | %s | %d/%d/%d/%d",
                    e.getKey(), bestT1, EvalReportWriter.pct(bestAcc), EvalReportWriter.pct(bestMiss),
                    EvalReportWriter.pct(bestFpr), btp, bfp, bfn, btn));
        }
    }

    /**
     * P0-5 在线验证：min-max 批内校准需要完整批内数组，无法离线仿真，必须切换 checker 语义校准模式后重跑全链路。
     * 启用：-Dsem.ablation.online=true
     */
    @Test
    @EnabledIfSystemProperty(named = "sem.ablation.online", matches = "true")
    @DisplayName("P0-5 在线消融：MINMAX 批内校准")
    void semanticMinMaxOnline() throws Exception {
        System.setProperty("gap046.debug", "false");
        LocalBgeEmbeddingClient bge = buildLocalBgeClient();
        System.out.println("\n[P0-5-ONLINE] MINMAX 批内校准在线验证（重跑生产链路）");
        printBest("linear(在线基线)", scoreOnlineAll(new ConsistencyChecker(), bge));
        ConsistencyChecker minmax = new ConsistencyChecker();
        minmax.setSemanticCalibration(ConsistencyChecker.SemanticCalibration.minmax());
        printBest("minmax(在线)", scoreOnlineAll(minmax, bge));
    }

    private static void printBest(String label, List<PairScore> scores) {
        double bestT1 = 0.8, bestAcc = -1, bestMiss = 1, bestFpr = 1;
        long btp = 0, bfp = 0, bfn = 0, btn = 0;
        for (int ti = 40; ti <= 90; ti += 2) {
            double t1 = ti / 100.0;
            long tp = 0, fp = 0, fn = 0, tn = 0;
            for (PairScore ps : scores) {
                boolean detected = ps.totalSimilarity < t1;
                if (ps.groundTruthDefective) { if (detected) tp++; else fn++; }
                else { if (detected) fp++; else tn++; }
            }
            double acc = EvalMetrics.defectAccuracy(tp, tn, fp, fn);
            double miss = EvalMetrics.missRate(tp, fn);
            double fpr = EvalMetrics.falsePositiveRate(fp, tn);
            if (acc > bestAcc || (acc == bestAcc && (miss + fpr) < (bestMiss + bestFpr))) {
                bestAcc = acc; bestMiss = miss; bestFpr = fpr; bestT1 = t1;
                btp = tp; bfp = fp; bfn = fn; btn = tn;
            }
        }
        System.out.println(String.format("[P0-5-ONLINE] %-20s | %.2f | %s | %s | %s | %d/%d/%d/%d",
                label, bestT1, EvalReportWriter.pct(bestAcc), EvalReportWriter.pct(bestMiss),
                EvalReportWriter.pct(bestFpr), btp, bfp, bfn, btn));
    }

    /** 用指定 checker 全链路打分（三工程 + BGE），返回对齐标注对得分；与 @BeforeAll runScoring 同口径 */
    private static List<PairScore> scoreOnlineAll(ConsistencyChecker checker, LocalBgeEmbeddingClient bge) throws Exception {
        List<PairScore> out = new ArrayList<>();
        Path datasetDir = resolveDatasetDir();
        JsonNode labelRoot = OM.readTree(Files.readString(datasetDir.resolve("consistency-labels.json")));
        Map<String, JsonNode> labelById = new HashMap<>();
        labelRoot.path("pairs").forEach(p -> labelById.put(p.path("id").asText(), p));

        long idSeq = 1;
        for (String source : SOURCES) {
            Path sourceDir = datasetDir.getParent().resolve(source);
            List<Requirement> requirements = parseRequirements(sourceDir.resolve("requirements.txt"), idSeq);
            idSeq += requirements.size();
            List<CodeUnit> codeUnits = parseCode(sourceDir, idSeq);
            idSeq += codeUnits.size();
            if (bge != null && bge.available()) {
                applyBgeSemanticVectors(bge, requirements, codeUnits);
            }
            Map<String, List<String>> classEvidence =
                    com.traceguard.util.ClassEvidenceScanner.scanConstants(sourceDir.resolve("code").toString());
            List<ConsistencyResult> results = checker.checkConsistency(
                    0L, 0L, requirements, codeUnits, null, classEvidence, ALPHA, BETA, GAMMA, 0.8, 0.5);

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
            for (Map.Entry<String, JsonNode> e : labelById.entrySet()) {
                JsonNode p = e.getValue();
                if (!source.equals(p.path("source").asText())) continue;
                if ("exclude".equals(p.path("scope").asText(""))) continue;
                String reqCode = p.path("requirementCode").asText("");
                if (reqCode.isEmpty()) continue;
                String cls = p.path("codeFile").asText("").replaceFirst("\\.java$", "");
                String key = reqCode + "#" + cls + "." + p.path("method").asText();
                ConsistencyResult r = index.get(key);
                if (r == null) continue;
                PairScore ps = new PairScore();
                ps.id = e.getKey();
                ps.source = source;
                ps.className = cls;
                ps.defectType = p.path("defectType").asText("");
                ps.groundTruthDefective = "defective".equals(p.path("label").asText());
                ps.totalSimilarity = r.getTotalSimilarity();
                ps.semanticSimilarity = r.getSemanticSimilarity();
                ps.constraintMatch = r.getConstraintMatchDegree();
                ps.invariantSatisfaction = r.getInvariantSatisfaction();
                ps.detectedMainType = r.getDefectType() == null ? "" : r.getDefectType();
                Requirement reqObj = reqById.get(r.getRequirementId());
                CodeUnit codeObj = codeById.get(r.getCodeUnitId());
                ps.reqText = reqObj.getOriginalText();
                ps.codeText = codeObj.getCodeContent();
                ps.defectRisk = CodeDefectPatternDetector.detectDefectRisk(ps.reqText, ps.codeText);
                out.add(ps);
            }
        }
        return out;
    }

    /**
     * P1-4 前置分析：detectDefectRisk 各子信号在缺陷对 vs 一致对上的判别力。
     * 启用：-Drisk.analyze=true。输出每信号两组均值/命中率，用于决定权重化合成方向
     * （判别力 = 缺陷对命中率高且一致对命中率低的信号应获高权重）。
     */
    @Test
    @EnabledIfSystemProperty(named = "risk.analyze", matches = "true")
    @DisplayName("P1-4 前置：defectRisk 信号判别力分析")
    void riskSignalAnalysis() throws Exception {
        Path datasetDir = resolveDatasetDir();
        Map<String, Map<String, List<String>>> classEvBySource = new HashMap<>();
        for (String source : SOURCES) {
            classEvBySource.put(source, com.traceguard.util.ClassEvidenceScanner.scanConstants(
                    datasetDir.getParent().resolve(source).resolve("code").toString()));
        }
        // 信号名 -> [defect 累计, consistent 累计]（同一时刻只跑一组，分开统计）
        java.util.Map<String, double[]> accDef = new LinkedHashMap<>();
        java.util.Map<String, double[]> accOk = new LinkedHashMap<>();
        java.util.Map<String, int[]> hitDef = new LinkedHashMap<>();
        java.util.Map<String, int[]> hitOk = new LinkedHashMap<>();
        long nDef = 0, nOk = 0;
        for (PairScore ps : pairScores) {
            String simple = ps.className;
            int dot = simple == null ? -1 : simple.lastIndexOf('.');
            if (simple != null && dot >= 0) simple = simple.substring(dot + 1);
            List<String> evidence = classEvBySource.getOrDefault(ps.source, java.util.Collections.emptyMap())
                    .getOrDefault(simple, java.util.Collections.emptyList());
            java.util.Map<String, Double> sig = CodeDefectPatternDetector.explainSignals(ps.reqText, ps.codeText, evidence);
            boolean def = ps.groundTruthDefective;
            if (def) nDef++; else nOk++;
            java.util.Map<String, double[]> acc = def ? accDef : accOk;
            java.util.Map<String, int[]> hit = def ? hitDef : hitOk;
            for (java.util.Map.Entry<String, Double> e : sig.entrySet()) {
                acc.computeIfAbsent(e.getKey(), k -> new double[1])[0] += e.getValue();
                if (e.getValue() > 0) hit.computeIfAbsent(e.getKey(), k -> new int[1])[0]++;
            }
        }
        System.out.println("\n[P1-4-RISK] 子信号判别力（缺陷对 n=" + nDef + " / 一致对 n=" + nOk + "）");
        System.out.println("[P1-4-RISK] signal | mean(def) | mean(ok) | hit%(def) | hit%(ok) | 判别方向");
        for (String key : sigOrder()) {
            double md = accDef.containsKey(key) ? accDef.get(key)[0] / Math.max(1, nDef) : 0;
            double mo = accOk.containsKey(key) ? accOk.get(key)[0] / Math.max(1, nOk) : 0;
            double hd = hitDef.containsKey(key) ? 100.0 * hitDef.get(key)[0] / Math.max(1, nDef) : 0;
            double ho = hitOk.containsKey(key) ? 100.0 * hitOk.get(key)[0] / Math.max(1, nOk) : 0;
            String dir = (md - mo) > 0.01 ? "缺陷侧↑" : ((mo - md) > 0.01 ? "一致侧↑(噪声)" : "≈ 无判别力");
            System.out.println(String.format("[P1-4-RISK] %-26s | %.3f | %.3f | %5.1f%% | %5.1f%% | %s",
                    key, md, mo, hd, ho, dir));
        }
    }

    /**
     * 剩余 FN/FP 画像（-Dfn.analyze=true）：在默认权重 + 推荐 t1 下列出全部漏检/误报对的
     * 三维得分、risk 及命中的子信号，用于判断下一步规则覆盖方向。
     */
    @Test
    @EnabledIfSystemProperty(named = "fn.analyze", matches = "true")
    @DisplayName("FN/FP 画像分析（默认权重 & t1=0.46）")
    void fnFpDetail() {
        final double T1 = 0.46;
        List<PairScore> fn = new ArrayList<>(), fp = new ArrayList<>();
        for (PairScore ps : pairScores) {
            boolean detected = ps.totalSimilarity < T1;
            if (ps.groundTruthDefective && !detected) fn.add(ps);
            else if (!ps.groundTruthDefective && detected) fp.add(ps);
        }
        System.out.println("\n[FN-FP] 默认权重下 t1=" + T1 + "：FN=" + fn.size() + " / FP=" + fp.size());
        System.out.println("[FN-FP] ===== 漏检 FN（缺陷被判一致）=====");
        for (PairScore ps : fn) {
            System.out.println(String.format("[FN-FP] FN %s | %s | total=%.3f sem=%.3f con=%.3f inv=%.3f risk=%.3f",
                    ps.id, ps.defectType, ps.totalSimilarity, ps.semanticSimilarity,
                    ps.constraintMatch, ps.invariantSatisfaction, ps.defectRisk));
            System.out.println("        REQ=" + truncate(ps.reqText, 120));
        }
        System.out.println("[FN-FP] ===== 误报 FP（一致被判缺陷）=====");
        for (PairScore ps : fp) {
            System.out.println(String.format("[FN-FP] FP %s | total=%.3f sem=%.3f con=%.3f inv=%.3f risk=%.3f",
                    ps.id, ps.totalSimilarity, ps.semanticSimilarity, ps.constraintMatch,
                    ps.invariantSatisfaction, ps.defectRisk));
            System.out.println("        REQ=" + truncate(ps.reqText, 120));
            System.out.println("        CODE=" + truncate(ps.codeText, 140));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ");
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static List<String> sigOrder() {
        return java.util.Arrays.asList(
                "stateMismatch", "numericMismatch", "paramValidationMissing", "logicInversion",
                "commonCodeBug", "impliedBusinessRuleMissing", "nullDereference", "stateFlowViolation",
                "refundFactor", "quantitativeBoundMismatch");
    }

    /**
     * P1-4 权重网格验证：按信号判别力分析结果配置权重，在线重跑生产链路比较四指标。
     * 启用：-Drisk.grid=true
     */
    @Test
    @EnabledIfSystemProperty(named = "risk.grid", matches = "true")
    @DisplayName("P1-4 权重网格（去噪基线 + 温和升权组合）")
    void riskWeightGrid() throws Exception {
        System.setProperty("gap046.debug", "false");
        LocalBgeEmbeddingClient bge = buildLocalBgeClient();
        System.out.println("\n[P1-4-GRID] defectRisk 权重网格（在线全链路，configureRiskWeights(null)=去噪默认）");

        CodeDefectPatternDetector.configureRiskWeights(null); // null → defaultWeights（去噪）
        printBest("default(去噪=基线)", scoreOnlineAll(new ConsistencyChecker(), bge));

        // 温和升权组合（全部以去噪 0 权重为底，仅放大判别力证据最强的少量信号，避免 w-strong 式整体放大引发误报）
        Map<String, Double> wTame = new LinkedHashMap<>();
        wTame.put("stateMismatch", 0.0);
        wTame.put("impliedBusinessRuleMissing", 0.0);
        wTame.put("numericMismatch", 2.0);          // 缺陷 0.084 / 一致 0.000（最强）
        wTame.put("quantitativeBoundMismatch", 1.5); // 缺陷 0.035 / 一致 0.000
        CodeDefectPatternDetector.configureRiskWeights(wTame);
        printBest("w-tame(num2+qBound1.5)", scoreOnlineAll(new ConsistencyChecker(), bge));

        Map<String, Double> wNumOnly = new LinkedHashMap<>(wTame);
        wNumOnly.remove("quantitativeBoundMismatch");
        wNumOnly.put("numericMismatch", 2.5);   // 极简组合：仅放大数值强信号
        CodeDefectPatternDetector.configureRiskWeights(wNumOnly);
        printBest("w-numOnly(num2.5)", scoreOnlineAll(new ConsistencyChecker(), bge));

        Map<String, Double> wLogic = new LinkedHashMap<>(wTame);
        wLogic.put("logicInversion", 1.5);           // 缺陷 0.081 / 一致 0.017
        wLogic.put("paramValidationMissing", 1.3);   // 0.108 / 0.041
        CodeDefectPatternDetector.configureRiskWeights(wLogic);
        printBest("w-logic(num2+qB1.5+inv1.5+pv1.3)", scoreOnlineAll(new ConsistencyChecker(), bge));

        Map<String, Double> wStrong = new LinkedHashMap<>(wLogic);
        wStrong.put("nullDereference", 1.2);
        wStrong.put("stateFlowViolation", 1.2);
        CodeDefectPatternDetector.configureRiskWeights(wStrong);
        printBest("w-strong(全强信号1.2~2.0)", scoreOnlineAll(new ConsistencyChecker(), bge));

        CodeDefectPatternDetector.configureRiskWeights(null); // 复位，避免影响同 JVM 内其它测试
    }

    /**
     * DEFECT_RISK_WEIGHT 网格（-Driskw.grid=true）：提高 risk 惩罚权重对 adjustedSim 的压缩作用。
     * FP 画像全部 risk=0 → 提高惩罚不影响 FP；FN 中 risk 已命中（0.3~0.65）但因 0.55 权重不足而漏检。
     */
    @Test
    @EnabledIfSystemProperty(named = "riskw.grid", matches = "true")
    @DisplayName("DEFECT_RISK_WEIGHT 网格")
    void defectRiskWeightGrid() throws Exception {
        System.setProperty("gap046.debug", "false");
        LocalBgeEmbeddingClient bge = buildLocalBgeClient();
        System.out.println("\n[RISKW-GRID] DEFECT_RISK_WEIGHT 网格（默认风险权重，仅变惩罚系数）");
        double[] candidates = {0.55, 0.7, 0.85, 1.0, 1.3};
        for (double w : candidates) {
            ConsistencyChecker.setDefectRiskWeight(w);
            printBest("w=" + w, scoreOnlineAll(new ConsistencyChecker(), bge));
        }
        ConsistencyChecker.setDefectRiskWeight(0.55); // 复位生产默认
    }

    /**
     * 缺陷清单评测基线（-Ddefect.list.eval=true）。
     * 将 generateDefects 产出的缺陷清单与 consistency-labels 的 in-scope 标注对（defective/consistent）对齐：
     *   defectiveRecall = 被检出的缺陷标注对 / 全部缺陷标注对（与阈值标定的 TP 口径互补，这里走真实 generateDefects）
     *   fpOnConsistent  = 系统对"标注一致对"误报缺陷的数量
     * 该评测为 P1-2（匈牙利匹配）/ P1-5（全集过滤）等 generateDefects 层改动提供"对错标尺"。
     */
    @Test
    @EnabledIfSystemProperty(named = "defect.list.eval", matches = "true")
    @DisplayName("缺陷清单评测基线（generateDefects vs labels）")
    void defectListEval() throws Exception {
        System.setProperty("gap046.debug", "false");
        LocalBgeEmbeddingClient bge = buildLocalBgeClient();
        Path datasetDir = resolveDatasetDir();
        JsonNode labelRoot = OM.readTree(Files.readString(datasetDir.resolve("consistency-labels.json")));
        long totalDef = 0, hitDef = 0, fpOnConsistent = 0, totalConsistent = 0;
        long oracleUpper = 0;   // 归属-对级正解的上限：defective 真对且判定层可报（total∈[0.5,0.8)）
        long idSeq = 1;
        for (String source : SOURCES) {
            Path sourceDir = datasetDir.getParent().resolve(source);
            List<Requirement> reqs = parseRequirements(sourceDir.resolve("requirements.txt"), idSeq);
            idSeq += reqs.size();
            List<CodeUnit> codes = parseCode(sourceDir, idSeq);
            idSeq += codes.size();
            if (bge != null && bge.available()) {
                applyBgeSemanticVectors(bge, reqs, codes);
            }
            Map<Long, Requirement> reqById = new HashMap<>();
            reqs.forEach(r -> reqById.put(r.getId(), r));
            Map<Long, CodeUnit> codeById = new HashMap<>();
            codes.forEach(c -> codeById.put(c.getId(), c));
            Map<String, Long> reqCodeId = new HashMap<>();
            reqs.forEach(r -> reqCodeId.put(r.getRequirementId(), r.getId()));
            Map<String, Long> methodCodeId = new HashMap<>();
            codes.forEach(c -> methodCodeId.put(c.getClassName() + "." + c.getMethodName(), c.getId()));

            // 真值对（in-scope）：requirementCode + 方法 -> label
            List<long[]> defTruth = new ArrayList<>();   // {reqId, codeId}
            List<long[]> consistentTruth = new ArrayList<>();
            for (JsonNode p : labelRoot.path("pairs")) {
                if (!source.equals(p.path("source").asText())) continue;
                if ("exclude".equals(p.path("scope").asText(""))) continue;
                String reqCode = p.path("requirementCode").asText("");
                if (reqCode.isEmpty()) continue;
                String cls = p.path("codeFile").asText("").replaceFirst("\\.java$", "");
                Long rid = reqCodeId.get(reqCode);
                Long cid = methodCodeId.get(cls + "." + p.path("method").asText());
                if (rid == null || cid == null) continue;
                long[] pair = {rid, cid};
                if ("defective".equals(p.path("label").asText())) defTruth.add(pair);
                else consistentTruth.add(pair);
            }

            Map<String, List<String>> classEvidence = com.traceguard.util.ClassEvidenceScanner
                    .scanConstants(sourceDir.resolve("code").toString());
            ConsistencyChecker checker = new ConsistencyChecker();
            List<ConsistencyResult> results = checker.checkConsistency(
                    0L, 0L, reqs, codes, null, classEvidence, ALPHA, BETA, GAMMA, 0.8, 0.5);
            List<Defect> defects = checker.generateDefects(0L, 0L, results, reqs, codes);
            Set<String> defectKeys = new HashSet<>();
            for (Defect d : defects) {
                if (d.getRequirementId() != null && d.getCodeUnitId() != null) {
                    defectKeys.add(d.getRequirementId() + "#" + d.getCodeUnitId());
                }
            }
            for (long[] p : defTruth) {
                totalDef++;
                if (defectKeys.contains(p[0] + "#" + p[1])) hitDef++;
            }
            // 归属能力上限（oracle）：labels 已给出"需求 r → 文件/方法 c"的正确答案（即归属 oracle）。
            // 若归属到位，缺陷按对级判定即可报出——可报条件 = 该对在生成阈值内判不一致且 ≥ 候选下限。
            for (ConsistencyResult rr : results) {
                if (rr.getRequirementId() == null || rr.getCodeUnitId() == null) continue;
                // 仅统计 defective 真对
                for (long[] p : defTruth) {
                    if (p[0] == rr.getRequirementId() && p[1] == rr.getCodeUnitId()) {
                        double t = rr.getTotalSimilarity();
                        if (t >= 0.5 && t < 0.8) { // 候选下限(0.5) ≤ total < 一致阈值(0.8)
                            oracleUpper++;
                        }
                        break;
                    }
                }
            }
            for (long[] p : consistentTruth) {
                totalConsistent++;
                if (defectKeys.contains(p[0] + "#" + p[1])) fpOnConsistent++;
            }
        }
        double recall = totalDef == 0 ? 0 : 100.0 * hitDef / totalDef;
        System.out.println(String.format(
                "\n[DEFECT-LIST] 缺陷清单评测（M：defective=%d / consistent=%d）\n[DEFECT-LIST] generateDefects 命中缺陷标注对=%d/%d (%.1f%%)，对一致标注对误报=%d/%d (%.1f%%)",
                totalDef, totalConsistent, hitDef, totalDef, recall, fpOnConsistent, totalConsistent,
                totalConsistent == 0 ? 0 : 100.0 * fpOnConsistent / totalConsistent));
        System.out.println(String.format(
                "[DEFECT-LIST] 归属oracle上限：判定层可报(0.5<=total<0.8)的 defective 真对 = %d/%d (%.1f%%)  ← 归属能力理论上限",
                oracleUpper, totalDef, totalDef == 0 ? 0 : 100.0 * oracleUpper / totalDef));
        assertTrue(totalDef > 0 && totalConsistent > 0, "评测集对齐失败");
    }

    private static double sigmoidVal(double x, double center, double width) {
        double w = width <= 0 ? 0.05 : width;
        double z = (x - center) / w;
        if (z > 30) return 1.0;
        if (z < -30) return 0.0;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** 生成阈值标定报告（GAP-023 留档） */
    private void writeCalibrationReport(List<double[]> sweep, double bestT1, double recommendedT2,
                                        double bestAcc, double bestMiss, double bestFpr) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# TraceGuard 阈值标定报告（GAP-023）\n\n");
        sb.append("> 本报告由 GAP-023 阈值标定测试自动生成（`mvn test -Dtest=ThresholdCalibrationEvalTest`）。\n");
        sb.append("> 生成时间：").append(LocalDateTime.now()).append("\n\n");
        sb.append("---\n\n");

        sb.append("## 一、标定方法\n\n");
        sb.append("- **数据集**：GAP-007 三个样例工程（ecommerce-order / api-service / exam-system），标注集 samples/dataset/consistency-labels.json。\n");
        sb.append("- **对齐标注对数**：").append(pairScores.size()).append("（范围外的需求缺失/代码超范围对不参与一致性矩阵标定）。\n");
        sb.append("- **计算链路**：生产 ConsistencyChecker（**标定链路权重** alpha=").append(ALPHA)
          .append("，beta=").append(BETA).append("，gamma=").append(GAMMA)
          .append("；与生产默认 0.4/0.35/0.25 不同，见 application.yml；");
        if (bgeEnabled) {
            sb.append("语义向量=本地 BGE（bge-small-zh-v1.5，ONNX 512 维，由 EMBEDDING_MODEL_PATH 或默认候选路径注入）");
        } else {
            sb.append("语义向量缺省走 TF-IDF + jieba + 中英词典降级路径");
        }
        sb.append("；GAP-046 规则增强：约束匹配度由「需求约束点×代码实现证据」驱动）。\n");
        sb.append("- **标定口径**：综合相似度 totalSimilarity < t1 判定为缺陷，按 GAP-007 四指标（AUD-02 定稿：检出即 TP，主类型单列）计算准确率/漏检率/误报率。\n\n");

        sb.append("## 二、推荐阈值\n\n");
        sb.append("| 参数 | 推荐值 | 说明 |\n|---|---|---|\n");
        sb.append("| t1（一致性阈值） | ").append(String.format("%.2f", bestT1)).append(" | totalSimilarity >= t1 判定一致，< t1 报缺陷 |\n");
        sb.append("| t2（严重度分级阈值） | ").append(String.format("%.2f", recommendedT2)).append(" | [t2, t1) 为一般不一致，< t2 为严重不一致 |\n");
        sb.append("| alpha / beta / gamma | ").append(ALPHA).append(" / ").append(BETA).append(" / ").append(GAMMA).append(" | 标定链路参数（GAP-046 约束主导；生产默认 0.4/0.35/0.25 见 application.yml） |\n\n");

        sb.append("## 三、标定结果（最优 t1）\n\n");
        sb.append("| 指标 | 值 | 目标 | 判定 |\n|---|---|---|---|\n");
        sb.append("| 缺陷检测准确率 | ").append(EvalReportWriter.pct(bestAcc)).append(" | >= 80% | ")
          .append(bestAcc >= 0.80 ? "达标" : "未达标").append(" |\n");
        sb.append("| 漏检率 | ").append(EvalReportWriter.pct(bestMiss)).append(" | <= 15% | ")
          .append(bestMiss <= 0.15 ? "达标" : "未达标").append(" |\n");
        sb.append("| 误报率 | ").append(EvalReportWriter.pct(bestFpr)).append(" | <= 10% | ")
          .append(bestFpr <= 0.10 ? "达标" : "未达标").append(" |\n\n");

        sb.append("## 四、t1 扫描明细\n\n");
        sb.append("| t1 | 准确率 | 漏检率 | 误报率 | TP | FP | FN | TN |\n|---|---|---|---|---|---|---|---|\n");
        for (double[] row : sweep) {
            sb.append("| ").append(String.format("%.2f", row[0]))
              .append(" | ").append(EvalReportWriter.pct(row[1]))
              .append(" | ").append(EvalReportWriter.pct(row[2]))
              .append(" | ").append(EvalReportWriter.pct(row[3]))
              .append(" | ").append((long) row[4]).append(" | ").append((long) row[5])
              .append(" | ").append((long) row[6]).append(" | ").append((long) row[7])
              .append(" |\n");
        }
        sb.append("\n");

        sb.append("## 五、分项得分分布（缺陷对 vs 一致对）\n\n");
        appendScoreDistribution(sb);
        appendCollinearityDiagnosis(sb);
        sb.append("\n## 六、结论与建议\n\n");
        sb.append("- 将 `traceguard.analysis.default-threshold-t1` 设为推荐值 ")
          .append(String.format("%.2f", bestT1)).append("，可在本数据集上取得最优缺陷检测准确率。\n");
        sb.append("- t2 仅影响\"一般/严重\"分级，不改变是否报缺陷；推荐值已保证缺陷对落在严重区间内有区分度。\n");
        sb.append("- **与生产默认的关系**：生产默认 T1=").append(String.format("%.2f", 0.8)).append("/T2=").append(String.format("%.2f", 0.5))
          .append("（application.yml `analysis.default-threshold-*`）为保守口径——漏检更低但误报更高（见扫描明细对应行）；")
          .append("推荐值 t1=").append(String.format("%.2f", bestT1)).append(" 为本数据集准确率最优。两者均未同时满足 SRS 指标目标，")
          .append("规则链路需结合 LLM 增强判定（见《评测报告-综合》缺陷检测章节）。分级边界以 SRS FR-CHECK-002 为准：Sim > T1 完全一致。\n");
        sb.append("- 分项得分分布用于核对 determineDefectType 的子类型阈值（cosSim 0.2 / conMatch 0.6 / invSat 0.7）");
        sb.append("在 AST 证据检测（GAP-005）真实分项下可达，避免与基值耦合导致的不可达分支。\n");
        sb.append("- 若后续接入形式化规约（Alloy）与真实语义向量，应重跑本标定并追加版本化记录，不覆盖历史。\n");

        Path report = resolveReportPath();
        Files.createDirectories(report.getParent());
        Files.write(report, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 输出缺陷对/一致对的三维分项得分均值与区间，支撑子类型阈值可达性核对 */
    private void appendScoreDistribution(StringBuilder sb) {
        double[][] dims = new double[2][3]; // [defective?][cos/con/inv] 累加
        long[] cnt = new long[2];
        double[] min = {1, 1, 1, 1, 1, 1};
        double[] max = {0, 0, 0, 0, 0, 0};
        for (PairScore ps : pairScores) {
            int g = ps.groundTruthDefective ? 0 : 1;
            cnt[g]++;
            double[] v = {ps.semanticSimilarity, ps.constraintMatch, ps.invariantSatisfaction};
            for (int d = 0; d < 3; d++) {
                dims[g][d] += v[d];
                int li = g * 3 + d;
                if (v[d] < min[li]) min[li] = v[d];
                if (v[d] > max[li]) max[li] = v[d];
            }
        }
        sb.append("| 分组 | 维度 | 均值 | 最小 | 最大 |\n|---|---|---|---|---|\n");
        String[] groups = {"缺陷对", "一致对"};
        String[] dimNames = {"语义相似度", "约束匹配度", "不变量满足度"};
        for (int g = 0; g < 2; g++) {
            for (int d = 0; d < 3; d++) {
                int li = g * 3 + d;
                double mean = cnt[g] > 0 ? dims[g][d] / cnt[g] : 0;
                sb.append("| ").append(groups[g]).append(" | ").append(dimNames[d])
                  .append(" | ").append(String.format("%.3f", mean))
                  .append(" | ").append(String.format("%.3f", min[li]))
                  .append(" | ").append(String.format("%.3f", max[li])).append(" |\n");
            }
        }
    }

    /**
     * P0-4 诊断：三维得分共线性（Pearson 相关 + VIF）+ 中性底分占比。
     * 输出用于判断「Con/Inv 是否由同一覆盖率信号驱动」及「语义维度区分度」，支撑解耦方案决策。
     */
    private void appendCollinearityDiagnosis(StringBuilder sb) {
        // 分组：0=缺陷对 1=一致对 2=全体
        String[] groupNames = {"缺陷对", "一致对", "全体"};
        double overallConInv = Double.NaN;
        sb.append("### 5.1 三维共线性诊断（P0-4，2026-09 新增）\n\n");
        sb.append("| 分组 | N | corr(sem,con) | corr(sem,inv) | **corr(con,inv)** | VIF(con~inv) | con=0.5 中性 | inv=0.5 中性 |\n|---|---|---|---|---|---|---|---|\n");
        for (int g = 0; g < 3; g++) {
            List<Double> sem = new ArrayList<>(), con = new ArrayList<>(), inv = new ArrayList<>();
            int neutralCon = 0, neutralInv = 0;
            for (PairScore ps : pairScores) {
                boolean def = ps.groundTruthDefective;
                boolean include = g == 2 || (g == 0 && def) || (g == 1 && !def);
                if (!include) continue;
                sem.add(ps.semanticSimilarity);
                con.add(ps.constraintMatch);
                inv.add(ps.invariantSatisfaction);
                if (Math.abs(ps.constraintMatch - 0.5) < 1e-9) neutralCon++;
                if (Math.abs(ps.invariantSatisfaction - 0.5) < 1e-9) neutralInv++;
            }
            int n = sem.size();
            double sc = pearson(sem, con);
            double si = pearson(sem, inv);
            double ci = pearson(con, inv);
            if (g == 2) {
                overallConInv = ci;
            }
            String vif = (!Double.isNaN(ci) && Math.abs(ci) < 1.0)
                    ? String.format("%.1f", 1.0 / (1.0 - ci * ci)) : "∞";
            sb.append("| ").append(groupNames[g]).append(" | ").append(n)
              .append(" | ").append(fmtCorr(sc))
              .append(" | ").append(fmtCorr(si))
              .append(" | **").append(fmtCorr(ci)).append("**")
              .append(" | ").append(vif)
              .append(" | ").append(neutralCon).append(" | ").append(neutralInv).append(" |\n");
        }
        sb.append("\n> 解读：corr(con,inv)≈1 表明无规约路径下 Con/Inv 由同一覆盖率信号驱动（简化 Inv=0.5+0.5·coverage 与 Con 共线，P0-4 解耦目标）；"
                + "con/inv=0.5 为无约束点需求的中性底分（FUN-08②），占比过高时该组判定实质退化为单维语义。\n\n");
        // P0-4 共线性门禁：全体 corr(con,inv) 若升破 0.95（近乎完全同源，基线 0.807），说明无规约路径的
        // 简化 Inv 与 Con 又退化为同一覆盖率信号，Con/Inv 解耦努力被回退，触发失败提醒。
        assertTrue(Double.isNaN(overallConInv) || overallConInv < 0.95,
                "Con/Inv 共线性门禁未通过：全体 corr(con,inv)=" + fmtCorr(overallConInv) + " >= 0.95（基线 0.807，2026-09-02）");
    }

    private static String fmtCorr(double v) {
        if (Double.isNaN(v)) return "—";
        return String.format("%.3f", v);
    }

    /** Pearson 相关系数；样本数<2 或某序列方差为 0 时返回 NaN */
    private static double pearson(List<Double> a, List<Double> b) {
        int n = Math.min(a.size(), b.size());
        if (n < 2) return Double.NaN;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a.get(i); mb += b.get(i); }
        ma /= n; mb /= n;
        double cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a.get(i) - ma, db = b.get(i) - mb;
            cov += da * db; va += da * da; vb += db * db;
        }
        if (va <= 0 || vb <= 0) return Double.NaN;
        return cov / Math.sqrt(va * vb);
    }

    // ==================== 数据加载 ====================

    /**
     * 解析 requirements.txt。
     * 兼容三种格式：
     *   1. "REQ-XXX: 单行文本"
     *   2. "REQ-XXX | 标题 | Priority" + 后续多行正文（正文合并到该需求，直到下一个 REQ- 标题行）
     *   3. 注释行（# 开头）与分隔线（--- / ====）直接忽略
     */
    private static List<Requirement> parseRequirements(Path reqFile, long startId) throws Exception {
        List<Requirement> list = new ArrayList<>();
        if (!Files.exists(reqFile)) return list;
        long id = startId;
        Requirement current = null;
        StringBuilder body = new StringBuilder();
        for (String line : Files.readAllLines(reqFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            // 分隔线/页眉页脚，跳过
            if (t.matches("[-=]{4,}") || t.startsWith("END OF REQUIREMENTS")
                    || t.startsWith("版本:") || t.startsWith("日期:") || t.startsWith("状态:")) {
                continue;
            }
            Matcher m = Pattern.compile("^(REQ-\\d+)\\s*[:|]?\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(t);
            if (m.matches()) {
                // 新需求开始，先提交上一个
                if (current != null) {
                    current.setOriginalText(body.toString().trim());
                }
                current = new Requirement();
                current.setId(id++);
                current.setRequirementId(m.group(1).toUpperCase(Locale.ROOT));
                body = new StringBuilder(m.group(2).trim());
                list.add(current);
            } else if (current != null) {
                // 正文续行，合并到当前需求
                if (body.length() > 0) body.append(' ');
                body.append(t);
            }
        }
        if (current != null) {
            current.setOriginalText(body.toString().trim());
        }
        return list;
    }

    /** 构造本地 BGE 客户端（FUN-04①）：从 EMBEDDING_MODEL_PATH / 固定候选路径加载模型，加载失败返回 null（回退 TF-IDF） */
    private static LocalBgeEmbeddingClient buildLocalBgeClient() {
        String modelPath = System.getenv("EMBEDDING_MODEL_PATH");
        if (modelPath == null || modelPath.trim().isEmpty()) {
            String[] candidates = {
                    "../models/bge-small-zh-v1.5/onnx/model.onnx",
                    "models/bge-small-zh-v1.5/onnx/model.onnx",
            };
            for (String c : candidates) {
                if (new File(c).isFile()) { modelPath = c; break; }
            }
        }
        if (modelPath == null || modelPath.trim().isEmpty()) {
            System.out.println("[GAP-023] 未找到 BGE 模型，标定走 TF-IDF+jieba 降级路径");
            return null;
        }
        LlmProperties props = new LlmProperties();
        LlmProperties.EmbeddingConfig ec = new LlmProperties.EmbeddingConfig();
        ec.setProvider("local");
        ec.setModelPath(modelPath);
        ec.setModelDim(512);
        ec.setNormalize(true);
        props.setEmbedding(ec);
        LocalBgeEmbeddingClient client = new LocalBgeEmbeddingClient(props);
        if (client.available()) {
            bgeEnabled = true;
            System.out.println("[GAP-023] BGE 语义向量已启用（模型=" + modelPath + "）");
        } else {
            System.out.println("[GAP-023] BGE 加载失败，标定走 TF-IDF+jieba 降级路径");
        }
        return client;
    }

    /** 用本地 BGE 为需求/代码单元填充语义向量（SemanticVectorUtil JSON 格式），使 ConsistencyChecker 走稠密向量路径 */
    private static void applyBgeSemanticVectors(LocalBgeEmbeddingClient bge, List<Requirement> requirements, List<CodeUnit> codeUnits) {
        for (Requirement r : requirements) {
            String text = r.getOriginalText();
            if (text == null || text.trim().isEmpty()) continue;
            float[] v = bge.embed(text);
            if (v != null) {
                r.setSemanticVector(SemanticVectorUtil.toJson(v, ""));
            }
        }
        for (CodeUnit u : codeUnits) {
            StringBuilder sb = new StringBuilder();
            if (u.getClassName() != null) sb.append(u.getClassName()).append(' ');
            if (u.getMethodName() != null) sb.append(u.getMethodName()).append(' ');
            if (u.getLogicDescription() != null) sb.append(u.getLogicDescription());
            if (sb.length() == 0) continue;
            float[] v = bge.embed(sb.toString());
            if (v != null) {
                u.setSemanticVector(SemanticVectorUtil.toJson(v, ""));
            }
        }
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

    /** 报告输出到项目根 docs/03-报告/阈值标定报告.md */
    private static Path resolveReportPath() {
        String userDir = System.getProperty("user.dir", ".");
        String[] candidates = {
                Paths.get(userDir, "..", "docs", "03-报告").toString(),
                Paths.get(userDir, "docs", "03-报告").toString(),
        };
        for (String c : candidates) {
            if (new File(c).isDirectory()) {
                return Paths.get(c, "阈值标定报告.md");
            }
        }
        return Paths.get(userDir, "docs", "03-报告", "阈值标定报告.md");
    }
}
