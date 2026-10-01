package com.traceguard.core;

import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import com.traceguard.util.CodeDefectPatternDetector;
import com.traceguard.util.FormalSpecParserUtil;
import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.RequirementConstraintExtractor;
import com.traceguard.util.SemanticVectorUtil;
import com.traceguard.util.SemanticVectorUtil.SemanticVector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 需求-代码一致性校验器（P2-3：门面类）。
 *
 * 职责边界（拆分后）：
 * <ul>
 *   <li>{@link SimilarityScorer} —— 三维打分（语义/约束/不变量）+ IDF/CFG 特征；</li>
 *   <li>{@link DefectMatcher}    —— 一致性状态/缺陷类型判定 + 缺陷清单生成；</li>
 *   <li>{@link DefectLocator}    —— 缺陷行号（AST 优先）定位；</li>
 *   <li>{@link DefectNarrator}   —— 缺陷原因/修复建议文案；</li>
 *   <li>本类 —— 配置面（阈值权重/并行度/校准模式）、R×C 配对编排与缓存预计算。</li>
 * </ul>
 *
 * 对外签名（checkConsistency / generateDefects）与拆分前完全一致。
 */
@Component
public class ConsistencyChecker {

    /** GAP-025：一致性计算块内并行线程数配置（0=使用默认 min(8, CPU核)） */
    @Value("${traceguard.perf.consistency-threads:0}")
    private int consistencyThreads;

    /**
     * FUN-04：代码超范围实现的负例抑制下限。
     * 未匹配到任何需求的代码单元，若其与所有需求的最大综合相似度不低于该值，
     * 视为"存在对应需求但相似度未达配对门槛"，不再武断判为超范围实现（降低误报）。
     */
    @Value("${traceguard.analysis.out-of-scope-sim-limit:0.5}")
    private double outOfScopeSimLimit = 0.5; // Java 默认值兜底：评测/测试直接 new 时 @Value 不注入，避免为 0 导致负例抑制误伤

    /** P0-5：语义维度校准模式（默认 LINEAR=历史 (1+cos)/2 透传，评测消融经 setSemanticCalibration 切换） */
    private SemanticCalibration semanticCalibration = SemanticCalibration.linear();

    public void setSemanticCalibration(SemanticCalibration semanticCalibration) {
        if (semanticCalibration != null) {
            this.semanticCalibration = semanticCalibration;
        }
    }

    /** GAP-025：一致性计算分块大小（每块 N 条需求，块间串行、块内并行，保证结果顺序与内存可控） */
    private static final int CONSISTENCY_CHUNK_SIZE = 32;

    /** P0-5：语义校准配置（线性透传 / sigmoid 温度缩放 / 批内 min-max） */
    public static final class SemanticCalibration {
        public enum Mode { LINEAR, SIGMOID, MINMAX }

        private final Mode mode;
        private final double center;
        private final double width;

        private SemanticCalibration(Mode mode, double center, double width) {
            this.mode = mode;
            this.center = center;
            this.width = width;
        }

        public static SemanticCalibration linear() {
            return new SemanticCalibration(Mode.LINEAR, 0, 0);
        }

        public static SemanticCalibration sigmoid(double center, double width) {
            return new SemanticCalibration(Mode.SIGMOID, center, width);
        }

        public static SemanticCalibration minmax() {
            return new SemanticCalibration(Mode.MINMAX, 0, 0);
        }

        public Mode getMode() { return mode; }
        public double getCenter() { return center; }
        public double getWidth() { return width; }
    }

    /** CQ-06：规则缺陷风险对综合相似度的惩罚权重（GAP-046，避免魔法值）。
     *  volatile + setter：供阈值标定网格评估（-Driskw.grid=true）寻优；生产默认 0.55。 */
    private static volatile double DEFECT_RISK_WEIGHT = 0.55;

    public static void setDefectRiskWeight(double weight) {
        DEFECT_RISK_WEIGHT = Math.max(0.0, Math.min(2.0, weight));
    }

    /** 当前生效的缺陷风险惩罚权重（诊断/报告用） */
    public static double getDefectRiskWeight() {
        return DEFECT_RISK_WEIGHT;
    }

    public List<ConsistencyResult> checkConsistency(Long taskId, Long projectId,
                                                     List<Requirement> requirements,
                                                     List<CodeUnit> codeUnits,
                                                     double alpha, double beta, double gamma,
                                                     double t1, double t2) {
        return checkConsistency(taskId, projectId, requirements, codeUnits, Collections.emptyMap(),
                alpha, beta, gamma, t1, t2);
    }

    /**
     * GAP-005 装配：specAlloyByReqId 为 requirementId -> Alloy 规约原文映射（来自 tg_formal_specification.alloy_code），
     * 为每对 (Ri, Cj) 计算时复用任务级缓存的 SpecModel 与 EvidenceSet，避免 R×C 重复解析。
     * 未传规约时 Con/Inv 两维度自动降级为关键词/结构启发式（保持既有行为）。
     */
    public List<ConsistencyResult> checkConsistency(Long taskId, Long projectId,
                                                     List<Requirement> requirements,
                                                     List<CodeUnit> codeUnits,
                                                     Map<Long, String> specAlloyByReqId,
                                                     double alpha, double beta, double gamma,
                                                     double t1, double t2) {
        // FUN-04b：未提供类级证据时按空证据处理（相关规则信号自动失效，保持既有行为）
        return checkConsistency(taskId, projectId, requirements, codeUnits, specAlloyByReqId,
                Collections.<String, List<String>>emptyMap(), alpha, beta, gamma, t1, t2);
    }

    /**
     * FUN-04b 全参重载：classEvidenceByClass = 简单类名 -> 类级字段/常量声明行（ClassEvidenceScanner 扫描产物）。
     * 提供后，缺陷模式检测可做跨方法的量化常量核对（需求显式数值边界 vs 类常量定义值），
     * 覆盖幂等窗口/限流阈值类缺陷；未提供的类不受影响。
     */
    public List<ConsistencyResult> checkConsistency(Long taskId, Long projectId,
                                                     List<Requirement> requirements,
                                                     List<CodeUnit> codeUnits,
                                                     Map<Long, String> specAlloyByReqId,
                                                     Map<String, List<String>> classEvidenceByClass,
                                                     double alpha, double beta, double gamma,
                                                     double t1, double t2) {
        List<ConsistencyResult> results = new ArrayList<>();
        if (requirements == null || requirements.isEmpty() || codeUnits == null || codeUnits.isEmpty()) {
            return results;
        }
        // FR-CODE-001 规则3（2.4 整改项）：类字段清单单元仅作展示，不参与一致性/语义比较
        final List<CodeUnit> methodUnits = codeUnits.stream()
                .filter(u -> !JavaCodeParserUtil.isFieldListUnit(u))
                .collect(java.util.stream.Collectors.toList());
        if (methodUnits.isEmpty()) {
            return results;
        }
        // 预计算TF-IDF：语料=全部需求+代码单元；每篇文档只分词一次，避免R×C配对重复计算
        Map<String, Double> idfMap = SimilarityScorer.buildIdfModel(requirements, methodUnits);
        Map<Long, Map<String, Double>> reqVectors = new HashMap<>();
        for (Requirement req : requirements) {
            reqVectors.put(req.getId(), SimilarityScorer.tokenize(SimilarityScorer.reqDoc(req)));
        }
        Map<Long, Map<String, Double>> codeVectors = new HashMap<>();
        for (CodeUnit code : methodUnits) {
            codeVectors.put(code.getId(), SimilarityScorer.tokenize(SimilarityScorer.codeDoc(code)));
        }
        // P0-2：稠密语义向量预解析（512 维 JSON 每对重复反序列化代价高，改为每单元一次，并行阶段仅读缓存）
        Map<Long, SemanticVector> reqSemVectors = new HashMap<>();
        for (Requirement req : requirements) {
            reqSemVectors.put(req.getId(), SemanticVectorUtil.parse(req.getSemanticVector()));
        }
        Map<Long, SemanticVector> codeSemVectors = new HashMap<>();
        for (CodeUnit code : methodUnits) {
            codeSemVectors.put(code.getId(), SemanticVectorUtil.parse(code.getSemanticVector()));
        }
        // P0-1：约束匹配代码侧预扫描（CodeProfile 每代码单元一次，替代 R×C 配对内反复正则扫描代码全文）
        Map<Long, RequirementConstraintExtractor.CodeProfile> codeProfileCache = new HashMap<>();
        // GAP-005 + GAP-025：任务级缓存预计算（SpecModel 按需求、CFG特征 按代码单元各解析一次），
        // 并行阶段仅读缓存，避免并发写 HashMap
        Map<Long, FormalSpecParserUtil.SpecModel> specCache = new HashMap<>();
        for (Requirement req : requirements) {
            specCache.put(req.getId(), FormalSpecParserUtil.parse(
                    specAlloyByReqId == null ? null : specAlloyByReqId.get(req.getId())));
        }
        // CQ-01：并发安全的 CFG 特征缓存——并行阶段 computeIfAbsent 可能触发并发写普通 HashMap（resize/死循环）；
        // 改 ConcurrentHashMap 且预填充"全量占位"（null 也放入），并行阶段 key 均已存在，仅 get/原子 computeIfAbsent
        Map<Long, SimilarityScorer.CfgFeatures> cfgFeaturesCache = new ConcurrentHashMap<>();
        // P1-5：detectDefectRisk 纯代码侧信号（commonCodeBug / nullDereference）每代码单元预扫描一次，
        // 消除 R×C 配对内对同一 code 的重复全文正则扫描（与原合成逐位等价）
        Map<Long, double[]> codeRiskPartsCache = new HashMap<>();
        for (CodeUnit code : methodUnits) {
            String codeContent = code.getCodeContent() == null ? "" : code.getCodeContent();
            codeProfileCache.put(code.getId(), RequirementConstraintExtractor.CodeProfile.of(codeContent));
            codeRiskPartsCache.put(code.getId(), new double[]{
                    CodeDefectPatternDetector.commonCodeBugComponent(code.getCodeContent()),
                    CodeDefectPatternDetector.nullDereferenceComponent(code.getCodeContent())});
            SimilarityScorer.CfgFeatures cf = SimilarityScorer.extractCfgFeatures(code.getCfgData());
            // T11 兜底：cfgData 解析失败（返回 null）时放默认空特征——ConcurrentHashMap 不允许 null value，
            // 且任何语言的 CFG 异常不应中断整个任务
            cfgFeaturesCache.put(code.getId(), cf != null ? cf : new SimilarityScorer.CfgFeatures()); // 全量占位（异常时空特征）
        }
        // GAP-025：一致性计算按需求分块（每块 N 条需求），块内并行、块间串行（保证结果顺序与内存可控）
        // CQ-05：复用共享线程池，避免每次 checkConsistency 创建/销毁线程池
        ExecutorService pool = consistencyPool();
        final SemanticCalibration calibration = semanticCalibration;
        try {
            for (int start = 0; start < requirements.size(); start += CONSISTENCY_CHUNK_SIZE) {
                int end = Math.min(start + CONSISTENCY_CHUNK_SIZE, requirements.size());
                List<CompletableFuture<List<ConsistencyResult>>> futures = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    final Requirement req = requirements.get(i);
                    futures.add(CompletableFuture.supplyAsync(() -> computeReqPairs(
                            taskId, projectId, req, methodUnits, specCache, codeProfileCache, cfgFeaturesCache,
                            idfMap, reqVectors, codeVectors, reqSemVectors, codeSemVectors, codeRiskPartsCache,
                            classEvidenceByClass, calibration, alpha, beta, gamma, t1, t2), pool));
                }
                for (CompletableFuture<List<ConsistencyResult>> f : futures) {
                    results.addAll(f.join());
                }
            }
        } finally {
            // CQ-05：共享线程池常驻复用，不做 shutdownNow（daemon 线程随 JVM 退出）
        }
        return results;
    }

    /** CQ-05：一致性计算共享线程池（懒创建、单例复用，避免每次 checkConsistency 反复创建/销毁） */
    private volatile ExecutorService consistencyPool;

    private ExecutorService consistencyPool() {
        ExecutorService p = consistencyPool;
        if (p == null) {
            synchronized (this) {
                p = consistencyPool;
                if (p == null) {
                    p = Executors.newFixedThreadPool(consistencyThreadPoolSize(), r -> {
                        Thread t = new Thread(r);
                        t.setName("consistency-check-shared");
                        t.setDaemon(true);
                        return t;
                    });
                    consistencyPool = p;
                }
            }
        }
        return p;
    }

    /** GAP-025：单条需求 Ri 与全部代码单元 Cj 的一致性计算（按 Cj 顺序返回，供块内并行调用） */
    private List<ConsistencyResult> computeReqPairs(Long taskId, Long projectId, Requirement req,
                                                    List<CodeUnit> codeUnits,
                                                    Map<Long, FormalSpecParserUtil.SpecModel> specCache,
                                                    Map<Long, RequirementConstraintExtractor.CodeProfile> codeProfileCache,
                                                    Map<Long, SimilarityScorer.CfgFeatures> cfgFeaturesCache,
                                                    Map<String, Double> idfMap,
                                                    Map<Long, Map<String, Double>> reqVectors,
                                                    Map<Long, Map<String, Double>> codeVectors,
                                                    Map<Long, SemanticVector> reqSemVectors,
                                                    Map<Long, SemanticVector> codeSemVectors,
                                                    Map<Long, double[]> codeRiskPartsCache,
                                                    Map<String, List<String>> classEvidenceByClass,
                                                    SemanticCalibration calibration,
                                                    double alpha, double beta, double gamma,
                                                    double t1, double t2) {
        List<ConsistencyResult> reqResults = new ArrayList<>(codeUnits.size());
        Map<String, Double> reqVec = reqVectors.getOrDefault(req.getId(), Collections.emptyMap());
        // P0-2：需求侧稠密向量预解析一次，供全部 Cj 复用（JSON 反序列化不再每对执行）
        SemanticVector reqSemVec = reqSemVectors.get(req.getId());
        // P1-5：需求侧文档文本每 req 拼接一次（原每对在 detectDefectRisk 前重复拼接）
        String reqDocText = SimilarityScorer.reqDoc(req);
        FormalSpecParserUtil.SpecModel specModel = specCache.get(req.getId());
        // P0-5：先计算该需求与全部代码单元的语义分，再按校准模式（LINEAR/SIGMOID/MINMAX）做批内或参数化变换，
        // 使语义维度具备批内相对区分度（默认 LINEAR 与历史行为完全一致）。
        double[] semanticScores = SimilarityScorer.calibrateSemantics(codeUnits, codeVectors, codeSemVectors,
                idfMap, reqVec, reqSemVec, calibration);
        for (int i = 0; i < codeUnits.size(); i++) {
            CodeUnit code = codeUnits.get(i);
            double cosSim = semanticScores[i];
            // P0-1：代码侧约束证据已由 CodeProfile 预扫描（每代码单元一次），配对内零全文正则扫描
            RequirementConstraintExtractor.CodeProfile codeProfile = codeProfileCache.get(code.getId());
            double conMatch = SimilarityScorer.calculateConstraintMatch(req, code, codeProfile);
            // GAP-025：使用预计算缓存的CFG特征（同一代码单元cfgData不变，避免R×C重复解析JSON）
            double invSat = SimilarityScorer.calculateInvariantSatisfactionWithCache(req, code, specModel,
                    code.getCfgData(), cfgFeaturesCache, codeProfile);
            double totalSim = alpha * cosSim + beta * conMatch + gamma * invSat;
            // GAP-046：规则模式为主基线——缺陷风险由三部分合成（约束缺失 + 异常模式 + 语义错位），
            // 使行为级/隐含规则/需求缺失类缺陷可被可靠压到阈值以下；一致对约束覆盖好则几乎不扣分。
            String clsName = code.getClassName() == null ? "" : code.getClassName();
            java.util.List<String> classEvidence = classEvidenceByClass == null
                    ? null : classEvidenceByClass.get(clsName.substring(clsName.lastIndexOf('.') + 1));
            // P1-5：代码侧分量已按单元预扫描（codeRiskPartsCache），此处仅计算需求相关信号
            double[] riskParts = codeRiskPartsCache.get(code.getId());
            double defectRisk = CodeDefectPatternDetector.detectDefectRisk(reqDocText, code.getCodeContent(),
                    classEvidence, riskParts == null ? 0.0 : riskParts[0],
                    riskParts == null ? 0.0 : riskParts[1]);
            double adjustedSim = totalSim * (1.0 - defectRisk * DEFECT_RISK_WEIGHT);
            ConsistencyResult result = new ConsistencyResult();
            result.setTaskId(taskId);
            result.setProjectId(projectId);
            result.setRequirementId(req.getId());
            result.setCodeUnitId(code.getId());
            result.setMatchId(UUID.randomUUID().toString().substring(0, 8));
            result.setSemanticSimilarity(cosSim);
            result.setConstraintMatchDegree(conMatch);
            result.setInvariantSatisfaction(invSat);
            result.setTotalSimilarity(adjustedSim);
            // B2 风险门控双通道：分数通道（adjustedSim < t1）OR 风险通道（defectRisk >= risk-gate）。
            // 去共线性重标定（A2）后，风险信号是独立高置信通道（P1-4 去噪一致对零命中），补足线性分数漏检。
            boolean riskDetected = defectRisk >= com.traceguard.config.ThresholdConfigHolder.riskGateMin();
            result.setConsistencyStatus(DefectMatcher.determineStatus(adjustedSim, t1, t2, riskDetected));
            String[] defectTypePair = DefectMatcher.determineDefectType(adjustedSim, cosSim, conMatch, invSat,
                    t1, t2, riskDetected);
            result.setDefectType(defectTypePair[0]);
            result.setDefectSubType(defectTypePair[1]);
            reqResults.add(result);
        }
        return reqResults;
    }

    private int consistencyThreadPoolSize() {
        // P2-1：上限由 4 提至 8——一致性计算为纯 CPU 密集（无外部 IO），8 核以上机器此前闲置过半算力；
        // 仍保留一致性计算与其它任务的隔离，避免与 Soot/文档解析等并发的整体过订阅
        int cores = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        return consistencyThreads > 0 ? consistencyThreads : cores;
    }

    /**
     * 由一致性结果生成缺陷清单（门面：委托 {@link DefectMatcher}）。
     */
    public List<Defect> generateDefects(Long taskId, Long projectId, List<ConsistencyResult> results,
                                         List<Requirement> requirements, List<CodeUnit> codeUnits) {
        return DefectMatcher.generateDefects(taskId, projectId, results, requirements, codeUnits,
                outOfScopeSimLimit);
    }
}
