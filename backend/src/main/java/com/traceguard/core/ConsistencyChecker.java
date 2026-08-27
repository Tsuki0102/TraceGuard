package com.traceguard.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.ConsistencyResult;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.SemanticVectorUtil;
import com.traceguard.util.SemanticVectorUtil.SemanticVector;
import com.traceguard.util.FormalSpecParserUtil;
import com.traceguard.util.ConstraintDetectorUtil;
import com.traceguard.util.BilingualDictLoader;
import com.traceguard.util.RequirementConstraintExtractor;
import com.traceguard.util.CodeDefectPatternDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConsistencyChecker {
    
    private static final Logger log = LoggerFactory.getLogger(ConsistencyChecker.class);

    /** GAP-025：一致性计算块内并行线程数配置（0=使用默认 min(4, CPU核)） */
    @Value("${traceguard.perf.consistency-threads:0}")
    private int consistencyThreads;

    /**
     * FUN-04：代码超范围实现的负例抑制下限。
     * 未匹配到任何需求的代码单元，若其与所有需求的最大综合相似度不低于该值，
     * 视为"存在对应需求但相似度未达配对门槛"，不再武断判为超范围实现（降低误报）。
     */
    @Value("${traceguard.analysis.out-of-scope-sim-limit:0.5}")
    private double outOfScopeSimLimit = 0.5; // Java 默认值兜底：评测/测试直接 new 时 @Value 不注入，避免为 0 导致负例抑制误伤

    /** GAP-025：一致性计算分块大小（每块 N 条需求，块间串行、块内并行，保证结果顺序与内存可控） */
    private static final int CONSISTENCY_CHUNK_SIZE = 32;

    /** 英文标识符（用于驼峰边界切分） */
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern ASCII_ONLY = Pattern.compile("[a-z0-9_]+");

    /** 语义扩展权重：中文词命中间词典后，其英文等价词按该权重并入向量 */
    private static final double EXPANSION_WEIGHT = 0.5;

    /** CQ-06：规则缺陷风险对综合相似度的惩罚权重（GAP-046，避免魔法值） */
    private static final double DEFECT_RISK_WEIGHT = 0.55;

    /**
     * GAP-024：中英语义词典（外部化加载，支持本地覆盖扩展）
     * 用于跨语言语义匹配：中文需求与英文代码标识符无字面交集，
     * 经词典扩展后在同一向量空间可比。
     */
    private static final Map<String, List<String>> ZH_EN_DICT = BilingualDictLoader.loadDictionary();

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

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
        Map<String, Double> idfMap = buildIdfModel(requirements, methodUnits);
        Map<Long, Map<String, Double>> reqVectors = new HashMap<>();
        for (Requirement req : requirements) {
            reqVectors.put(req.getId(), tokenize(reqDoc(req)));
        }
        Map<Long, Map<String, Double>> codeVectors = new HashMap<>();
        for (CodeUnit code : methodUnits) {
            codeVectors.put(code.getId(), tokenize(codeDoc(code)));
        }
        // GAP-005 + GAP-025：任务级缓存预计算（SpecModel 按需求、EvidenceSet/CFG特征 按代码单元各解析一次），
        // 并行阶段仅读缓存，避免并发写 HashMap
        Map<Long, FormalSpecParserUtil.SpecModel> specCache = new HashMap<>();
        for (Requirement req : requirements) {
            specCache.put(req.getId(), FormalSpecParserUtil.parse(
                    specAlloyByReqId == null ? null : specAlloyByReqId.get(req.getId())));
        }
        Map<Long, ConstraintDetectorUtil.EvidenceSet> evidenceCache = new HashMap<>();
        // CQ-01：并发安全的 CFG 特征缓存——并行阶段 computeIfAbsent 可能触发并发写普通 HashMap（resize/死循环）；
        // 改 ConcurrentHashMap 且预填充"全量占位"（null 也放入），并行阶段 key 均已存在，仅 get/原子 computeIfAbsent
        Map<Long, CfgFeatures> cfgFeaturesCache = new ConcurrentHashMap<>();
        for (CodeUnit code : methodUnits) {
            evidenceCache.put(code.getId(), ConstraintDetectorUtil.detect(code.getCodeContent()));
            CfgFeatures cf = extractCfgFeatures(code.getCfgData());
            cfgFeaturesCache.put(code.getId(), cf); // 全量占位（含 null）
        }
        // GAP-025：一致性计算按需求分块（每块 N 条需求），块内并行、块间串行（保证结果顺序与内存可控）
        // CQ-05：复用共享线程池，避免每次 checkConsistency 创建/销毁线程池
        ExecutorService pool = consistencyPool();
        try {
            for (int start = 0; start < requirements.size(); start += CONSISTENCY_CHUNK_SIZE) {
                int end = Math.min(start + CONSISTENCY_CHUNK_SIZE, requirements.size());
                List<CompletableFuture<List<ConsistencyResult>>> futures = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    final Requirement req = requirements.get(i);
                    futures.add(CompletableFuture.supplyAsync(() -> computeReqPairs(
                            taskId, projectId, req, methodUnits, specCache, evidenceCache, cfgFeaturesCache,
                            idfMap, reqVectors, codeVectors, classEvidenceByClass, alpha, beta, gamma, t1, t2), pool));
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
                                                    Map<Long, ConstraintDetectorUtil.EvidenceSet> evidenceCache,
                                                    Map<Long, CfgFeatures> cfgFeaturesCache,
                                                    Map<String, Double> idfMap,
                                                    Map<Long, Map<String, Double>> reqVectors,
                                                    Map<Long, Map<String, Double>> codeVectors,
                                                    Map<String, List<String>> classEvidenceByClass,
                                                    double alpha, double beta, double gamma,
                                                    double t1, double t2) {
        List<ConsistencyResult> reqResults = new ArrayList<>(codeUnits.size());
        Map<String, Double> reqVec = reqVectors.getOrDefault(req.getId(), Collections.emptyMap());
        FormalSpecParserUtil.SpecModel specModel = specCache.get(req.getId());
        for (CodeUnit code : codeUnits) {
            Map<String, Double> codeVec = codeVectors.getOrDefault(code.getId(), Collections.emptyMap());
            double cosSim = calculateCosineSimilarity(reqVec, codeVec, idfMap,
                    req.getSemanticVector(), code.getSemanticVector());
            ConstraintDetectorUtil.EvidenceSet evidence = evidenceCache.get(code.getId());
            double conMatch = calculateConstraintMatch(req, code, specModel, evidence);
            // GAP-025：使用预计算缓存的CFG特征（同一代码单元cfgData不变，避免R×C重复解析JSON）
            double invSat = calculateInvariantSatisfactionWithCache(req, code, specModel, code.getCfgData(), cfgFeaturesCache);
            double totalSim = alpha * cosSim + beta * conMatch + gamma * invSat;
            // GAP-046：规则模式为主基线——缺陷风险由三部分合成（约束缺失 + 异常模式 + 语义错位），
            // 使行为级/隐含规则/需求缺失类缺陷可被可靠压到阈值以下；一致对约束覆盖好则几乎不扣分。
            String clsName = code.getClassName() == null ? "" : code.getClassName();
            java.util.List<String> classEvidence = classEvidenceByClass == null
                    ? null : classEvidenceByClass.get(clsName.substring(clsName.lastIndexOf('.') + 1));
            double defectRisk = synthesizeDefectRisk(reqDoc(req), code.getCodeContent(), conMatch, cosSim, classEvidence);
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
            result.setConsistencyStatus(determineStatus(adjustedSim, t1, t2));
            String[] defectTypePair = determineDefectType(adjustedSim, cosSim, conMatch, invSat, t1, t2);
            result.setDefectType(defectTypePair[0]);
            result.setDefectSubType(defectTypePair[1]);
            reqResults.add(result);
        }
        return reqResults;
    }

    /**
     * GAP-046：规则模式缺陷风险（增强项）。
     * Con 维度已由 RequirementConstraintExtractor.constraintMatch 承担「约束缺失」主判据（结构化比对），
     * 此处仅叠加代码异常模式信号（空 catch / 数组越界 / 比较器反身性 / 除零等），
     * 避免双重惩罚过强导致一致对误报。
     */
    private double synthesizeDefectRisk(String reqDoc, String codeContent, double conMatch, double cosSim) {
        return synthesizeDefectRisk(reqDoc, codeContent, conMatch, cosSim, null);
    }

    /** FUN-04b：带类级证据的缺陷风险合成（quantitativeBoundMismatch 信号需要类常量清单） */
    private double synthesizeDefectRisk(String reqDoc, String codeContent, double conMatch, double cosSim,
                                        java.util.List<String> classEvidence) {
        if (StrUtil.isBlank(reqDoc) || StrUtil.isBlank(codeContent)) {
            return 0.0;
        }
        return clamp01(CodeDefectPatternDetector.detectDefectRisk(reqDoc, codeContent, classEvidence));
    }

    private int consistencyThreadPoolSize() {
        int cores = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        return consistencyThreads > 0 ? consistencyThreads : cores;
    }

    /** 需求文档文本：原文+类型+约束规则 */
    private String reqDoc(Requirement req) {
        StringBuilder sb = new StringBuilder();
        if (req.getOriginalText() != null) sb.append(req.getOriginalText());
        if (req.getRequirementType() != null) sb.append(" ").append(req.getRequirementType());
        if (req.getConstraintRules() != null) sb.append(" ").append(req.getConstraintRules());
        return sb.toString();
    }

    /** 代码单元文档文本：类名+方法名+逻辑说明+代码内容+语义向量特征词 */
    private String codeDoc(CodeUnit code) {
        StringBuilder sb = new StringBuilder();
        if (code.getClassName() != null) sb.append(code.getClassName());
        if (code.getMethodName() != null) sb.append(" ").append(code.getMethodName());
        if (code.getLogicDescription() != null) sb.append(" ").append(code.getLogicDescription());
        if (code.getCodeContent() != null) sb.append(" ").append(code.getCodeContent());
        String vecTerms = extractVectorTerms(code.getSemanticVector());
        if (!vecTerms.isEmpty()) sb.append(" ").append(vecTerms);
        return sb.toString();
    }

    /** 从语义向量JSON（SemanticVectorUtil生成）提取特征词串，兼容空值与非法JSON */
    private String extractVectorTerms(String semanticVector) {
        if (StrUtil.isBlank(semanticVector)) return "";
        try {
            return JSONUtil.parseObj(semanticVector).getStr("terms", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 构建IDF词典：文档集合=全部需求文本+全部代码单元文本
     * idf(t) = ln((N+1)/(df+1)) + 1，平滑处理保证高频词权重趋近1而非0
     */
    private Map<String, Double> buildIdfModel(List<Requirement> requirements, List<CodeUnit> codeUnits) {
        Map<String, Integer> df = new HashMap<>();
        int n = 0;
        for (Requirement req : requirements) {
            n++;
            for (String t : tokenize(reqDoc(req)).keySet()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        for (CodeUnit code : codeUnits) {
            n++;
            for (String t : tokenize(codeDoc(code)).keySet()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }
        return idf;
    }

    public List<Defect> generateDefects(Long taskId, Long projectId, List<ConsistencyResult> results,
                                         List<Requirement> requirements, List<CodeUnit> codeUnits) {
        List<Defect> defects = new ArrayList<>();
        Map<Long, Requirement> reqMap = new HashMap<>();
        for (Requirement r : requirements) reqMap.put(r.getId(), r);
        Map<Long, CodeUnit> codeMap = new HashMap<>();
        for (CodeUnit c : codeUnits) codeMap.put(c.getId(), c);
        Set<Long> matchedReqs = new HashSet<>();
        Set<Long> matchedCodes = new HashSet<>();
        results.sort((a, b) -> Double.compare(b.getTotalSimilarity(), a.getTotalSimilarity()));
        for (ConsistencyResult res : results) {
            if ("serious_inconsistent".equals(res.getConsistencyStatus()) ||
                "general_inconsistent".equals(res.getConsistencyStatus())) {
                if (matchedReqs.contains(res.getRequirementId()) || matchedCodes.contains(res.getCodeUnitId())) {
                    continue;
                }
                Requirement req = reqMap.get(res.getRequirementId());
                CodeUnit code = codeMap.get(res.getCodeUnitId());
                if (req == null || code == null) continue;
                Defect defect = new Defect();
                defect.setTaskId(taskId);
                defect.setProjectId(projectId);
                defect.setConsistencyResultId(res.getId());
                defect.setRequirementId(res.getRequirementId());
                defect.setCodeUnitId(res.getCodeUnitId());
                defect.setDefectId("DEF-" + UUID.randomUUID().toString().substring(0, 8));
                defect.setDefectLevel("serious_inconsistent".equals(res.getConsistencyStatus()) ? "serious" : "general");
                defect.setDefectType(res.getDefectType());
                defect.setSubType(res.getDefectSubType() != null ? res.getDefectSubType() : res.getDefectType());
                defect.setDefectReason(generateDefectReason(res, req, code));
                defect.setRepairSuggestion(generateRepairSuggestion(res, req, code));
                defect.setCodeSnippet(code.getCodeContent() != null ?
                        code.getCodeContent().substring(0, Math.min(500, code.getCodeContent().length())) : "");
                defect.setRequirementText(req.getOriginalText());
                defect.setDefectLine(locateDefectLine(res, req, code));
                defects.add(defect);
                matchedReqs.add(res.getRequirementId());
                matchedCodes.add(res.getCodeUnitId());
            } else if ("consistent".equals(res.getConsistencyStatus())) {
                matchedReqs.add(res.getRequirementId());
                matchedCodes.add(res.getCodeUnitId());
            }
        }
        for (Requirement req : requirements) {
            if (!matchedReqs.contains(req.getId())) {
                Defect defect = new Defect();
                defect.setTaskId(taskId);
                defect.setProjectId(projectId);
                defect.setRequirementId(req.getId());
                defect.setDefectId("DEF-" + UUID.randomUUID().toString().substring(0, 8));
                defect.setDefectLevel("serious");
                defect.setDefectType("需求缺失");
                defect.setSubType("需求缺失");
                defect.setDefectReason("需求\"" + req.getRequirementId() + "\"在代码中未找到对应实现");
                defect.setRepairSuggestion("请根据需求补充相应的代码实现");
                defect.setRequirementText(req.getOriginalText());
                defects.add(defect);
            }
        }
        // FUN-04：负例抑制——统计每个代码单元与所有需求的最大综合相似度
        Map<Long, Double> codeMaxSim = new HashMap<>();
        for (ConsistencyResult res : results) {
            if (res.getCodeUnitId() != null) {
                codeMaxSim.merge(res.getCodeUnitId(), res.getTotalSimilarity(), Math::max);
            }
        }
        for (CodeUnit code : codeUnits) {
            if (!matchedCodes.contains(code.getId())) {
                // FUN-04：若该代码单元与任一需求的最大相似度 ≥ 下限，视为存在相近需求（覆盖不足而非超范围），抑制该缺陷
                double maxSim = codeMaxSim.getOrDefault(code.getId(), 0.0);
                if (maxSim >= outOfScopeSimLimit) {
                    continue;
                }
                Defect defect = new Defect();
                defect.setTaskId(taskId);
                defect.setProjectId(projectId);
                defect.setCodeUnitId(code.getId());
                defect.setDefectId("DEF-" + UUID.randomUUID().toString().substring(0, 8));
                defect.setDefectLevel("general");
                defect.setDefectType("代码超范围实现");
                defect.setSubType("代码超范围实现");
                defect.setDefectReason("代码方法" + code.getClassName() + "." + code.getMethodName() + "未对应到任何需求条目");
                defect.setRepairSuggestion("请确认该方法是否为必要实现，或补充对应的需求文档");
                defect.setCodeSnippet(code.getCodeContent() != null ?
                        code.getCodeContent().substring(0, Math.min(500, code.getCodeContent().length())) : "");
                defect.setDefectLine(code.getStartLine() != null && code.getStartLine() > 0 ? code.getStartLine() : null);
                defects.add(defect);
            }
        }
        return defects;
    }

    /**
     * GAP-004 升级：向量余弦优先，值域映射 [0,1]；
     * 任一方无向量 -> 降级现有 TF-IDF + jieba + 词典路径（原样保留）。
     */
    private double calculateCosineSimilarity(Map<String, Double> reqTf, Map<String, Double> codeTf,
                                             Map<String, Double> idfMap,
                                             String reqSemVecJson, String codeSemVecJson) {
        // 向量路径：双端均有稠密向量且维度一致 -> (1+cos)/2 映射到 [0,1]
        SemanticVector reqVec = SemanticVectorUtil.parse(reqSemVecJson);
        SemanticVector codeVec = SemanticVectorUtil.parse(codeSemVecJson);
        if (reqVec.hasVector() && codeVec.hasVector() && reqVec.getDim() == codeVec.getDim()) {
            float[] rv = reqVec.getVector();
            float[] cv = codeVec.getVector();
            double dot = 0, rNorm = 0, cNorm = 0;
            for (int i = 0; i < rv.length; i++) {
                dot += (double) rv[i] * cv[i];
                rNorm += (double) rv[i] * rv[i];
                cNorm += (double) cv[i] * cv[i];
            }
            if (rNorm > 0 && cNorm > 0) {
                double cos = dot / (Math.sqrt(rNorm) * Math.sqrt(cNorm));
                return (1.0 + cos) / 2.0;  // 值域映射 [0,1]
            }
        }
        // 降级：TF-IDF + jieba + 词典路径（原样保留）
        return tfidfCosine(reqTf, codeTf, idfMap);
    }

    /** TF-IDF 加权余弦相似度（降级路径，原逻辑保留） */
    private double tfidfCosine(Map<String, Double> reqTf, Map<String, Double> codeTf,
                                Map<String, Double> idfMap) {
        if (reqTf.isEmpty() || codeTf.isEmpty()) return 0.3;
        double dot = 0, norm1 = 0, norm2 = 0;
        for (Map.Entry<String, Double> e : reqTf.entrySet()) {
            double w = e.getValue() * idfMap.getOrDefault(e.getKey(), 1.0);
            norm1 += w * w;
            Double codeFreq = codeTf.get(e.getKey());
            if (codeFreq != null) {
                dot += w * codeFreq * idfMap.getOrDefault(e.getKey(), 1.0);
            }
        }
        for (Map.Entry<String, Double> e : codeTf.entrySet()) {
            double w = e.getValue() * idfMap.getOrDefault(e.getKey(), 1.0);
            norm2 += w * w;
        }
        if (norm1 == 0 || norm2 == 0) return 0.3;
        return Math.min(1.0, dot / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    /**
     * GAP-005 + GAP-046：Con 维度——规约约束 vs 代码实现证据覆盖度。
     * GAP-046 主判据：统一采用 RequirementConstraintExtractor.constraintMatch（需求约束点 × 代码实现证据
     * 的结构化比对），替代原中性底分 calculateSimplifiedConstraintMatch，解决 Con 维度近常量无判别力的根因
     * （AUD-02）。仅在需求/代码为空等退化场景下回退中性底分。
     */
    private double calculateConstraintMatch(Requirement req, CodeUnit code,
                                            FormalSpecParserUtil.SpecModel specModel,
                                            ConstraintDetectorUtil.EvidenceSet evidence) {
        try {
            String reqDoc = reqDoc(req);
            String codeContent = code.getCodeContent();
            if (StrUtil.isBlank(reqDoc) || StrUtil.isBlank(codeContent)) {
                return calculateSimplifiedConstraintMatch(req, code);
            }
            // GAP-046：约束点 × 代码证据的结构化比对，具备缺陷判别力
            double cm = RequirementConstraintExtractor.constraintMatch(reqDoc, codeContent);
            if (cm < 0) {
                // FUN-13：需求无可提取约束点（英文/无约束语义）→ 回退中性启发式，避免被高估/低估主导匹配
                return calculateSimplifiedConstraintMatch(req, code);
            }
            return clamp01(cm);
        } catch (Exception e) {
            log.error("计算约束匹配度失败: req={}, code={}", req.getRequirementId(),
                    code.getClassName() + "." + code.getMethodName(), e);
            return calculateSimplifiedConstraintMatch(req, code);
        }
    }

    /**
     * GAP-025：不变量满足度计算（支持 CFG 特征缓存）。
     * 同一代码单元的 cfgData 在 R×C 配对中重复使用，CFG 特征只需提取一次。
     */
    private double calculateInvariantSatisfactionWithCache(Requirement req, CodeUnit code,
                                                           FormalSpecParserUtil.SpecModel specModel,
                                                           String cfgData,
                                                           Map<Long, CfgFeatures> cfgFeaturesCache) {
        try {
            if (specModel == null || specModel.getInvariants().isEmpty() || StrUtil.isBlank(cfgData)) {
                return calculateSimplifiedInvariantSatisfaction(req, code);
            }
            // GAP-025：从缓存获取 CFG 特征（避免重复解析 JSON）
            CfgFeatures cf = cfgFeaturesCache.computeIfAbsent(code.getId(),
                    cid -> extractCfgFeatures(cfgData));
            if (cf == null) {
                return calculateSimplifiedInvariantSatisfaction(req, code);
            }
            // 规约侧结构要求：状态转移谓词/多状态 -> 需要分支结构；转移(all =>) -> 需要循环；异常约束 -> 需要异常路径
            boolean specRequiresBranch = specModel.getInvariants().stream()
                    .anyMatch(FormalSpecParserUtil.InvariantClause::isHasTransition)
                    || specModel.getInvariants().stream().anyMatch(i -> i.getStates().size() > 1);
            boolean specRequiresLoop = specModel.getInvariants().stream().anyMatch(
                    i -> i.getClauseText().toLowerCase().contains("all ") && i.getClauseText().contains("=>"));
            boolean specRequiresExc = specModel.getConstraints().stream()
                    .anyMatch(c -> c.getKind() == FormalSpecParserUtil.ConstraintKind.EXCEPTION_PATH);
            double sum = 0;
            int items = 0;
            // 不匹配按"规约要求但缺失"扣减至 0.3；"规约未要求而存在"不扣
            if (specRequiresBranch) { sum += cf.branchPresent ? 1.0 : 0.3; items++; }
            if (specRequiresLoop) { sum += cf.loopPresent ? 1.0 : 0.3; items++; }
            if (specRequiresExc) { sum += cf.exceptionEdges > 0 ? 1.0 : 0.3; items++; }
            // GAP-045：Kripke 标签语义增强——需求状态标签 L(s) 中的原子命题(AP)被代码实现的比例，
            // 作为 Inv 维度额外信号，使"需求显式声明可判真伪命题"与代码实现对齐。
            double apSat = calculateAtomicPropositionSatisfaction(req, code);
            if (items == 0) {
                // 规格无结构项可判时，优先以 AP 满足度作为 Inv（AP 缺失则回退 simplified）
                return apSat >= 0 ? clamp01(apSat) : calculateSimplifiedInvariantSatisfaction(req, code);
            }
            if (apSat >= 0) { sum += apSat; items++; }
            return clamp01(sum / items);
        } catch (Exception e) {
            log.error("计算不变量满足度失败: req={}, code={}", req.getRequirementId(),
                    code.getClassName() + "." + code.getMethodName(), e);
            return calculateSimplifiedInvariantSatisfaction(req, code);
        }
    }

    /**
     * GAP-005：Inv 维度——CFG 结构特征 vs 规约不变量符合度（按布尔匹配加权平均）。
     * 规约无不变量 / 无 CFG / 特征提取失败 -> 降级结构启发式。
     * 
     * @deprecated 使用 {@link #calculateInvariantSatisfactionWithCache} 替代（支持缓存）
     */
    @Deprecated
    private double calculateInvariantSatisfaction(Requirement req, CodeUnit code,
                                                  FormalSpecParserUtil.SpecModel specModel,
                                                  String cfgData) {
        return calculateInvariantSatisfactionWithCache(req, code, specModel, cfgData, new HashMap<>());
    }

    /**
     * GAP-045：原子命题(AP)满足度——需求状态标签 L(s) 中声明的可判真伪命题，
     * 与代码实现内容的约束匹配均值 [0,1]；无 AP 或解析失败返回 -1（跳过）。
     * 使 Kripke 模型中"需求显式命题"真正参与 Inv 维度判定。
     */
    private double calculateAtomicPropositionSatisfaction(Requirement req, CodeUnit code) {
        String apField = req.getAtomicPropositions();
        if (StrUtil.isBlank(apField) || "[]".equals(apField.trim())) {
            return -1;
        }
        String codeText = code.getCodeContent() != null ? code.getCodeContent() : "";
        if (codeText.isEmpty()) {
            return -1;
        }
        // 原子命题以逗号分隔；每个命题作为约束点，与代码做约束匹配
        String[] props = apField.split(",");
        double sum = 0;
        int n = 0;
        for (String p : props) {
            String ap = p.trim();
            if (ap.isEmpty()) continue;
            // 含比较符(≥/≤/=)的命题：检查代码是否体现该约束主题
            double m = RequirementConstraintExtractor.constraintMatch(ap, codeText);
            if (m >= 0) {
                sum += clamp01(m);
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    /** CFG 结构特征（GAP-005 Inv 维度，从 CodeUnit.cfgData JSON 提取） */
    private static class CfgFeatures {
        boolean branchPresent;
        boolean loopPresent;
        int exceptionEdges;
    }

    /** 从 cfgData JSON（Soot 字节码级或 AST 级同构格式）提取结构特征；解析失败返回 null（触发降级） */
    private CfgFeatures extractCfgFeatures(String cfgData) {
        try {
            cn.hutool.json.JSONObject cfg = JSONUtil.parseObj(cfgData);
            cn.hutool.json.JSONArray nodes = cfg.getJSONArray("nodes");
            cn.hutool.json.JSONArray edges = cfg.getJSONArray("edges");
            CfgFeatures f = new CfgFeatures();
            java.util.Map<Integer, Integer> idIndex = new HashMap<>();
            if (nodes != null) {
                int i = 0;
                for (Object o : nodes) {
                    cn.hutool.json.JSONObject n = (cn.hutool.json.JSONObject) o;
                    idIndex.put(n.getInt("id"), i++);
                    String type = n.getStr("type", "");
                    if ("if".equals(type) || "switch".equals(type) || "goto".equals(type)) {
                        f.branchPresent = true;
                    }
                }
            }
            if (edges != null) {
                for (Object o : edges) {
                    cn.hutool.json.JSONObject e = (cn.hutool.json.JSONObject) o;
                    if ("catch".equals(e.getStr("label", ""))) {
                        f.exceptionEdges++;
                    }
                    Integer fi = idIndex.get(e.getInt("from"));
                    Integer ti = idIndex.get(e.getInt("to"));
                    if (fi != null && ti != null && ti <= fi) {
                        f.loopPresent = true; // 回边（goto 循环结构）
                    }
                }
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private double clamp01(double v) {
        return Math.min(1.0, Math.max(0.0, v));
    }

    /**
     * GAP-046：约束匹配度（回退逻辑）——由「需求约束点 × 代码实现证据」覆盖度驱动，
     * 替代近常量启发式，使「需求要求约束但代码未实现」的缺陷对 Con 显著下降。
     * 需求含可计分约束点时走证据集结构化比对；无约束点（extract 为空）或异常时回退中性 0.5（FUN-08②）。
     */
    private double calculateSimplifiedConstraintMatch(Requirement req, CodeUnit code) {
        try {
            String reqText = req.getOriginalText();
            String codeText = code.getCodeContent() != null ? code.getCodeContent() : "";
            double coverage = RequirementConstraintExtractor.constraintMatch(reqText, codeText);
            if (coverage >= 0) {
                // 覆盖度计分基础上保留少量中性底分，避免纯结构比对将合法实现压得过低
                return clamp01(0.35 + 0.65 * coverage);
            }
        } catch (Exception e) {
            log.warn("需求约束点提取失败，回退中性约束匹配: {}", e.getMessage());
        }
        // FUN-08②：无约束点需求（extract 为空）无法评估约束实现——回退中性 0.5，与 Inv 对称（FUN-13 同口径）。
        // 原中文关键词兜底（"不能为空"→"!= null"、"验证"→"if/validate" 等）无区分度且不可靠，已移除
        return 0.5;
    }

    /**
     * GAP-046：简化的不变量满足度（回退逻辑）——由「需求约束点 × 代码实现证据」覆盖度驱动。
     * 需求含约束点时代码未实现对应证据则 Inv 下降；无约束点回退结构启发式。
     */
    private double calculateSimplifiedInvariantSatisfaction(Requirement req, CodeUnit code) {
        try {
            String reqText = req.getOriginalText();
            String codeText = code.getCodeContent() != null ? code.getCodeContent() : "";
            double coverage = RequirementConstraintExtractor.constraintMatch(reqText, codeText);
            if (coverage >= 0) {
                // 不变量满足度与约束实现呈正相关：约束覆盖率低 -> 不变量满足度低
                return clamp01(0.5 + 0.5 * coverage);
            }
        } catch (Exception e) {
            log.warn("需求约束点提取失败，回退不变量启发式: {}", e.getMessage());
        }
        // FUN-13：无约束点需求不变量满足度取中性 0.5。
        // 原回退启发式给 return/public/花括号等"所有方法都具备"的结构加分（0.6~0.9），
        // 会把无关联英文需求的不变量维度系统性抬高，导致跨语言匹配排序错乱。
        return 0.5;
    }

    /**
     * 一致性分级判定（对齐 SRS FR-CHECK-002 业务规则1）：
     * Sim > T1 → 完全一致；T2 ≤ Sim ≤ T1 → 一般不一致；Sim < T2 → 严重不一致。
     * 注意：SRS 强一致边界为「> T1」（原实现误用 >=），此处按文档口径修正（4.1 优化项）。
     */
    private String determineStatus(double sim, double t1, double t2) {
        if (sim > t1) return "consistent";
        if (sim >= t2) return "general_inconsistent";
        return "serious_inconsistent";
    }

    /**
     * GAP-020：determineDefectType 返回 [主类型, 子类型] 二元组。
     * 主类型统一为 FR-CHECK-003 的 4 类口径，子类型保留原细分标签。
     */
    private String[] determineDefectType(double totalSim, double cosSim, double conMatch, double invSat, double t1, double t2) {
        if (totalSim >= t1) return new String[]{"", ""};
        String subType;
        // GAP-046：约束覆盖率（需求约束点×代码证据）是强判别信号，优先于语义相关性。
        // 需求要求约束而代码未实现 -> 约束条件不满足；语义弱相关（词面不重叠）但在约束满足前提下 -> 逻辑偏离。
        // CQ-06：子阈值经 ThresholdConfigHolder 配置化（默认 0.6/0.7/0.2，与历史行为一致）
        if (conMatch < com.traceguard.config.ThresholdConfigHolder.conMatchMin()) {
            subType = "约束条件不满足"; // 原值即主类型，子类型同主类型
        } else if (invSat < com.traceguard.config.ThresholdConfigHolder.invSatMin()) {
            subType = "不变量不满足";
        } else if (cosSim < com.traceguard.config.ThresholdConfigHolder.cosSimMin()) {
            subType = "逻辑偏离";
        } else {
            subType = "需求代码不匹配";
        }
        String mainType = DefectTypes.toMainType(subType, invSat, conMatch);
        return new String[]{mainType, subType};
    }

    /**
     * 2.6 整改：定位缺陷命中的具体代码行号（绝对行号）。
     * 基于缺陷子类型，从代码单元内容中匹配最相关的证据行，叠加 {@link CodeUnit#startLine} 得到源文件绝对行号。
     * 无法定位时回退到方法起始行（startLine），仍满足 SRS「定位到代码行号」要求。
     */
    private Integer locateDefectLine(ConsistencyResult res, Requirement req, CodeUnit code) {
        String subType = res.getDefectSubType() != null ? res.getDefectSubType() : res.getDefectType();
        String codeContent = code.getCodeContent() != null ? code.getCodeContent() : "";
        Integer base = code.getStartLine() != null && code.getStartLine() > 0 ? code.getStartLine() : null;
        if (codeContent.isEmpty()) {
            return base;
        }
        String[] lines = codeContent.split("\n", -1);
        Integer offset = null;

        String lower = subType.toLowerCase(Locale.ROOT);
        // 状态错误类：定位含状态常量/状态字段的代码行
        if (lower.contains("状态") || lower.contains("不变量") || lower.contains("约束条件不满足")) {
            offset = findLineByKeywords(lines, "status", "state", "pending", "paid", "published",
                    "active", "inactive", "success", "failed", "completed", "unpublished");
        }
        // 数值阈值/越界：定位含数值常量的代码行（与需求数值比对不一致优先）
        if (offset == null && (lower.contains("阈值") || lower.contains("越界") || lower.contains("数值") || lower.contains("范围"))) {
            offset = findNumericLine(lines, req.getOriginalText());
        }
        // 约束/校验缺失：定位含校验关键词的行；参数校验缺失优先 if/非空判断
        if (offset == null && (lower.contains("约束") || lower.contains("校验") || lower.contains("验证"))) {
            offset = findLineByKeywords(lines, "if", "validate", "check", "assert", "require", "null", "empty", "blank");
        }
        // 业务逻辑/需求代码不匹配：定位方法体首条可执行语句（第一个非空非注释行），否则方法首行
        if (offset == null) {
            offset = findFirstStatementLine(lines);
        }
        // 兜底：方法首行
        if (offset == null) {
            offset = 0;
        }
        return base != null ? base + offset : offset + 1;
    }

    /** 在代码行中查找首个包含任一关键词（忽略大小写）的行，返回 0-based 偏移；无则返回 null */
    private Integer findLineByKeywords(String[] lines, String... keywords) {
        String[] lowerKw = Arrays.stream(keywords).map(k -> k.toLowerCase(Locale.ROOT)).toArray(String[]::new);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            for (String k : lowerKw) {
                if (l.contains(k)) return i;
            }
        }
        return null;
    }

    /** 定位含数值常量的代码行；若需求含数值，优先不匹配需求数值的代码行；返回 0-based 偏移 */
    private Integer findNumericLine(String[] lines, String reqText) {
        Set<Integer> reqNums = new HashSet<>();
        if (reqText != null) {
            Matcher m = Pattern.compile("\\b(\\d{1,4})\\b").matcher(reqText);
            while (m.find()) reqNums.add(Integer.parseInt(m.group(1)));
        }
        Integer fallback = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = Pattern.compile("\\b(\\d{1,4})\\b").matcher(lines[i]);
            if (m.find()) {
                if (fallback == null) fallback = i;
                int n = Integer.parseInt(m.group(1));
                // 优先定位与需求数值不一致的代码行（潜在越界/阈值错误）
                if (!reqNums.isEmpty() && !reqNums.contains(n)) {
                    return i;
                }
            }
        }
        return fallback;
    }

    /** 定位方法体首个非空、非纯注释的语句行（0-based 偏移）；无则返回 null */
    private Integer findFirstStatementLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            if (trimmed.equals("{") || trimmed.equals("}")) continue;
            return i;
        }
        return null;
    }

    private String generateDefectReason(ConsistencyResult res, Requirement req, CodeUnit code) {
        StringBuilder reason = new StringBuilder();
        reason.append("需求与代码一致性判定为").append("general_inconsistent".equals(res.getConsistencyStatus()) ? "一般不一致" : "严重不一致").append("。");
        reason.append("综合相似度: ").append(String.format("%.2f", res.getTotalSimilarity())).append("; ");
        reason.append("语义相似度: ").append(String.format("%.2f", res.getSemanticSimilarity())).append("; ");
        reason.append("约束匹配度: ").append(String.format("%.2f", res.getConstraintMatchDegree())).append("; ");
        reason.append("不变量满足度: ").append(String.format("%.2f", res.getInvariantSatisfaction())).append("。");
        if (res.getSemanticSimilarity() < 0.3) {
            reason.append("语义相似度较低，需求与代码实现的业务逻辑可能存在偏差。");
        }
        if (res.getConstraintMatchDegree() < 0.6) {
            reason.append("约束匹配度不足，需求中定义的约束条件在代码中可能未完整实现。");
        }
        return reason.toString();
    }

    private String generateRepairSuggestion(ConsistencyResult res, Requirement req, CodeUnit code) {
        StringBuilder suggestion = new StringBuilder();
        if (res.getSemanticSimilarity() < 0.3) {
            suggestion.append("1. 检查").append(code.getClassName()).append(".").append(code.getMethodName()).append("方法的实现逻辑，确认是否与需求\"").append(req.getRequirementId()).append("\"对应;\n");
            suggestion.append("2. 如方法不对应，请将需求匹配到正确的方法；如方法实现有误，请调整业务逻辑。\n");
        }
        if (res.getConstraintMatchDegree() < 0.6) {
            suggestion.append("3. 检查需求中定义的约束条件（如参数校验、空值检查、异常处理等），确保代码中完整实现。\n");
        }
        if (suggestion.length() == 0) {
            suggestion.append("请对照需求原文检查代码实现，调整业务逻辑、约束条件处理，确保需求完整正确实现。");
        }
        return suggestion.toString();
    }

    /**
     * 分词并计算TF（归一化词频）：
     * - 英文：小写化 + 驼峰/下划线边界切分（驼峰切分在小写化之前进行）
     * - 中文：jieba分词，保留>=2字词
     */
    private Map<String, Double> tokenize(String text) {
        Map<String, Double> tf = new HashMap<>();
        if (StrUtil.isBlank(text)) return tf;
        List<String> terms = new ArrayList<>();
        // 驼峰/下划线边界切分（需保留原始大小写识别边界）
        Matcher ascii = ASCII_WORD.matcher(text);
        while (ascii.find()) {
            terms.addAll(splitCamelCase(ascii.group()));
        }
        // 整体小写化后按词切分；纯ASCII词直接保留，含中文的段交由jieba
        String cleaned = text.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5_]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!cleaned.isEmpty()) {
            for (String part : cleaned.split(" ")) {
                if (ASCII_ONLY.matcher(part).matches()) {
                    terms.add(part);
                } else {
                    for (String t : segmenter.sentenceProcess(part)) {
                        String trimmed = t.trim().toLowerCase();
                        if (!trimmed.isEmpty()) terms.add(trimmed);
                    }
                }
            }
        }
        double total = 0;
        for (String t : terms) {
            if (t.length() < 2) continue;
            tf.merge(t, 1.0, Double::sum);
            total += 1;
        }
        // 中英语义扩展：命中间词典的中文词按EXPANSION_WEIGHT并入英文等价词，
        // 使中文需求与英文代码标识符在同一向量空间可跨语言匹配
        for (Map.Entry<String, List<String>> entry : ZH_EN_DICT.entrySet()) {
            if (tf.getOrDefault(entry.getKey(), 0.0) > 0) {
                for (String en : entry.getValue()) {
                    tf.merge(en, EXPANSION_WEIGHT, Double::sum);
                    total += EXPANSION_WEIGHT;
                }
            }
        }
        if (total > 0) {
            for (Map.Entry<String, Double> e : tf.entrySet()) {
                e.setValue(e.getValue() / total);
            }
        }
        return tf;
    }

    private List<String> splitCamelCase(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
                continue;
            }
            if (Character.isUpperCase(c) && current.length() > 0) {
                result.add(current.toString().toLowerCase());
                current = new StringBuilder();
            }
            current.append(Character.toLowerCase(c));
        }
        if (current.length() > 0) result.add(current.toString().toLowerCase());
        return result;
    }
}
