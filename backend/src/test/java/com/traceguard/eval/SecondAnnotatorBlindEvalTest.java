package com.traceguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.core.ConsistencyChecker;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Requirement;
import com.traceguard.service.ConsistencyJudge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A4 ①③：LLM 盲评第二标注 + 验证集 LLM 主口径复测（-Dsecond.annotator=true 启用，需本地/云端 LLM）。
 *
 * 盲评协议：对全部 in-scope 标注对，仅以（需求原文, 代码, 类级证据）走生产双判定管线
 * （ConsistencyJudge.judgeDual，FUN-04b），全程不读 consistency-labels 的 label/defectType 字段，
 * 产出与第一评独立的第二评标注 -> samples/dataset/second-annotation.json。
 *
 * 产出：
 *   1. Cohen's kappa（第一评=人工标注，第二评=LLM 盲评），二分类口径 consistent/defective；
 *   2. 验证集（split=validation）上 LLM 主口径四指标（acc/miss/fpr），与 tune 集 LLM 口径互证；
 *   3. secondAnnotator 回填数据（由脚本写入 consistency-labels.json）。
 *
 * 方法学说明（如实）：第二评为本地 LLM 盲评（未见第一评标注），非独立人工复标；
 * kappa 度量的是"人工 vs LLM 盲评"的一致性。人工第二标注人复标后可用本工具重跑替换。
 */
@DisplayName("A4 LLM 盲评第二标注 + 验证集 LLM 口径（-Dsecond.annotator=true）")
@EnabledIfSystemProperty(named = "second.annotator", matches = "true")
class SecondAnnotatorBlindEvalTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String[] SOURCES = {"ecommerce-order", "api-service", "exam-system",
            "ticket-system", "library-system"};
    private static final String ANNOTATOR_ID = "llm:qwen2.5-coder:14b-blind(judgeDual)";

    private static class BlindVerdict {
        String id;
        String source;
        String split;
        boolean secondConsistent;   // 盲评结论
        String secondDefectType;    // 盲评缺陷类型（一致为空）
        String reason;
        String decisionPath;
        // 以下仅在内存中用于口径计算，不写入盲评文件（保持盲评文件无真值污染）
        transient boolean firstDefective;
    }

    private static final List<BlindVerdict> verdicts = new ArrayList<>();

    @BeforeAll
    static void runBlindAnnotation() throws Exception {
        ConsistencyJudge judge = LlmJudgeFactory.build();
        assertNotNull(judge, "LLM 不可用：请配置 LLM_PROVIDER/CODELLAMA_MODEL 或 QWEN_API_KEY");

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
            Map<String, List<String>> classEvidence =
                    com.traceguard.util.ClassEvidenceScanner.scanConstants(sourceDir.resolve("code").toString());

            List<ConsistencyResult> results = checker.checkConsistency(
                    0L, 0L, requirements, codeUnits, null, classEvidence, 0.5, 0.2, 0.3, 0.52, 0.5);
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
                Requirement req = reqById.get(r.getRequirementId());
                CodeUnit code = codeById.get(r.getCodeUnitId());

                // 盲评：仅需求原文 + 代码 + 类级证据，不读 label/defectType
                String clsName = code.getClassName() == null ? "" : code.getClassName();
                String simpleCls = clsName.contains(".")
                        ? clsName.substring(clsName.lastIndexOf('.') + 1) : clsName;
                List<String> evLines = classEvidence.getOrDefault(simpleCls, List.of());
                ConsistencyJudge.JudgeContext ctx =
                        ConsistencyJudge.JudgeContext.fromEvidence(evLines, code.getMethodName());
                double pairRisk = com.traceguard.util.CodeDefectPatternDetector.detectDefectRisk(
                        req.getOriginalText(), code.getCodeContent(), evLines);
                ConsistencyJudge.Judgement j = judge.judgeDual(
                        req.getOriginalText(), code.getCodeContent(),
                        r.getSemanticSimilarity(), r.getConstraintMatchDegree(),
                        r.getInvariantSatisfaction(), r.getTotalSimilarity(), r.getDefectType(),
                        ctx, pairRisk);
                BlindVerdict v = new BlindVerdict();
                v.id = e.getKey();
                v.source = source;
                v.split = p.path("split").asText(
                        source.startsWith("ticket") || source.startsWith("library") ? "validation" : "tune");
                v.firstDefective = "defective".equals(p.path("label").asText());
                if (j == null) {
                    // LLM 不可判 -> 第二评弃权（不计入 kappa/口径，与标注规范"无效判定"一致）
                    continue;
                }
                v.secondConsistent = j.isConsistent();
                v.secondDefectType = j.isConsistent() ? "" : j.getDefectType();
                v.reason = j.getReason();
                v.decisionPath = j.getDecisionPath();
                verdicts.add(v);
            }
        }
        assertFalse(verdicts.isEmpty(), "盲评产出为空");

        // 写盲评文件（不含第一评真值）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("annotator", ANNOTATOR_ID);
        out.put("mode", "生产双判定管线 judgeDual（变体A/B 交叉 + 规则仲裁 + 数值归属过滤）；"
                + "盲评全程未读第一评 label/defectType 字段");
        out.put("generatedAt", java.time.LocalDateTime.now().toString());
        Map<String, Object> verdictMap = new LinkedHashMap<>();
        for (BlindVerdict v : verdicts) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("consistent", v.secondConsistent);
            n.put("defectType", v.secondDefectType);
            n.put("reason", v.reason);
            n.put("decisionPath", v.decisionPath);
            verdictMap.put(v.id, n);
        }
        out.put("verdicts", verdictMap);
        Path outPath = datasetDir.resolve("second-annotation.json");
        Files.write(outPath, OM.writerWithDefaultPrettyPrinter()
                .writeValueAsString(out).getBytes(StandardCharsets.UTF_8));
        System.out.println("[SECOND] 盲评完成：" + verdicts.size() + " 对 -> " + outPath);
    }

    @Test
    @DisplayName("① Cohen's kappa（人工第一评 vs LLM 盲评第二评）")
    void computeKappa() {
        long agree = 0;
        long n = verdicts.size();
        long aDef = 0, bDef = 0, bothDef = 0;
        for (BlindVerdict v : verdicts) {
            boolean secondDefective = !v.secondConsistent;
            if (v.firstDefective == secondDefective) agree++;
            if (v.firstDefective) aDef++;
            if (secondDefective) bDef++;
            if (v.firstDefective && secondDefective) bothDef++;
        }
        double po = (double) agree / n;
        double pe = ((double) aDef / n) * ((double) bDef / n)
                + ((double) (n - aDef) / n) * ((double) (n - bDef) / n);
        double kappa = pe == 1.0 ? 1.0 : (po - pe) / (1.0 - pe);
        System.out.println(String.format("[SECOND-KAPPA] n=%d po=%.4f pe=%.4f kappa=%.4f（门槛 >=0.70）",
                n, po, pe, kappa));
        System.out.println(String.format("[SECOND-KAPPA] 第一评缺陷 %d / 第二评缺陷 %d / 双评均缺陷 %d",
                aDef, bDef, bothDef));
        assertTrue(kappa >= 0.70,
                "双评 kappa 门禁未通过：kappa=" + String.format("%.4f", kappa) + " < 0.70");
    }

    @Test
    @DisplayName("③ 验证集 LLM 主口径复测（split=validation，盲评判定）")
    void validationLlmMetrics() {
        long tp = 0, fp = 0, fn = 0, tn = 0;
        int n = 0;
        for (BlindVerdict v : verdicts) {
            if (!"validation".equals(v.split)) continue;
            n++;
            boolean detected = !v.secondConsistent;
            if (v.firstDefective) {
                if (detected) tp++; else fn++;
            } else {
                if (detected) fp++; else tn++;
            }
        }
        assertTrue(n > 0, "验证集盲评对为空");
        double acc = EvalMetrics.defectAccuracy(tp, tn, fp, fn);
        double miss = EvalMetrics.missRate(tp, fn);
        double fpr = EvalMetrics.falsePositiveRate(fp, tn);
        System.out.println(String.format(
                "[SECOND-VALIDATION-LLM] 验证集 LLM 主口径：M=%d acc=%s miss=%s fpr=%s TP/FP/FN/TN=%d/%d/%d/%d（门槛 acc>=80/miss<=15/fpr<=10）",
                n, EvalReportWriter.pct(acc), EvalReportWriter.pct(miss), EvalReportWriter.pct(fpr), tp, fp, fn, tn));
        // 与 SRS 门槛同口径（DefectDetectionEvalTest tune 口径一致）
        assertTrue(acc >= 0.80, "验证集 LLM 口径 acc 未达标："
                + EvalReportWriter.pct(acc) + " < 80%");
        assertTrue(miss <= 0.15, "验证集 LLM 口径 miss 未达标："
                + EvalReportWriter.pct(miss) + " > 15%");
        assertTrue(fpr <= 0.10, "验证集 LLM 口径 fpr 未达标："
                + EvalReportWriter.pct(fpr) + " > 10%");
    }

    // ==================== 数据装载（与 ThresholdCalibrationEvalTest 同口径） ====================

    private static List<Requirement> parseRequirements(Path reqFile, long startId) throws Exception {
        List<Requirement> list = new ArrayList<>();
        if (!Files.exists(reqFile)) return list;
        long id = startId;
        Requirement current = null;
        StringBuilder body = new StringBuilder();
        for (String line : Files.readAllLines(reqFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.matches("[-=]{4,}") || t.startsWith("版本:") || t.startsWith("日期:") || t.startsWith("状态:")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(REQ-[A-Z]*\\d+)\\s*[:|]?\\s*(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(t);
            if (m.matches()) {
                if (current != null) current.setOriginalText(body.toString().trim());
                current = new Requirement();
                current.setId(id++);
                current.setRequirementId(m.group(1).toUpperCase(Locale.ROOT));
                body = new StringBuilder(m.group(2).trim());
                list.add(current);
            } else if (current != null) {
                body.append(" ").append(t);
            }
        }
        if (current != null) current.setOriginalText(body.toString().trim());
        return list;
    }

    private static List<CodeUnit> parseCode(Path sourceDir, long startId) {
        Path codeDir = sourceDir.resolve("code");
        List<CodeUnit> units = new com.traceguard.util.JavaCodeParserUtil().parseProject(codeDir.toString());
        long id = startId;
        for (CodeUnit u : units) u.setId(id++);
        return units;
    }

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
        throw new IllegalStateException("未找到 samples/dataset/consistency-labels.json");
    }
}
