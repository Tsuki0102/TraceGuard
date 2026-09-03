package com.traceguard.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.traceguard.entity.CodeUnit;
import com.traceguard.entity.Requirement;
import com.traceguard.util.BilingualDictLoader;
import com.traceguard.util.FormalSpecParserUtil;
import com.traceguard.util.RequirementConstraintExtractor;
import com.traceguard.util.SemanticVectorUtil.SemanticVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2-3：三维相似度打分器（从 ConsistencyChecker 拆出，职责=语义/约束/不变量三维打分 + IDF/CFG 缓存特征）。
 *
 * 本类为无状态静态工具（线程安全），供 ConsistencyChecker 门面在 R×C 配对循环中调用；
 * 所有方法与拆分前逐位等价，未改变任何打分口径。
 */
public final class SimilarityScorer {

    private static final Logger log = LoggerFactory.getLogger(SimilarityScorer.class);

    private SimilarityScorer() {}

    /** 英文标识符（用于驼峰边界切分） */
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern ASCII_ONLY = Pattern.compile("[a-z0-9_]+");

    /** 语义扩展权重：中文词命中间词典后，其英文等价词按该权重并入向量 */
    private static final double EXPANSION_WEIGHT = 0.5;

    /**
     * GAP-024：中英语义词典（外部化加载，支持本地覆盖扩展）
     * 用于跨语言语义匹配：中文需求与英文代码标识符无字面交集，
     * 经词典扩展后在同一向量空间可比。
     */
    private static final Map<String, List<String>> ZH_EN_DICT = BilingualDictLoader.loadDictionary();

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    // ==================== 文档文本 ====================

    /** 需求文档文本：原文+类型+约束规则 */
    public static String reqDoc(Requirement req) {
        StringBuilder sb = new StringBuilder();
        if (req.getOriginalText() != null) sb.append(req.getOriginalText());
        if (req.getRequirementType() != null) sb.append(" ").append(req.getRequirementType());
        if (req.getConstraintRules() != null) sb.append(" ").append(req.getConstraintRules());
        return sb.toString();
    }

    /** 代码单元文档文本：类名+方法名+逻辑说明+代码内容+语义向量特征词 */
    public static String codeDoc(CodeUnit code) {
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
    public static String extractVectorTerms(String semanticVector) {
        if (StrUtil.isBlank(semanticVector)) return "";
        try {
            return JSONUtil.parseObj(semanticVector).getStr("terms", "");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== IDF / 分词 ====================

    /**
     * 构建IDF词典：文档集合=全部需求文本+全部代码单元文本
     * idf(t) = ln((N+1)/(df+1)) + 1，平滑处理保证高频词权重趋近1而非0
     */
    public static Map<String, Double> buildIdfModel(List<Requirement> requirements, List<CodeUnit> codeUnits) {
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

    /**
     * 分词并计算TF（归一化词频）：
     * - 英文：小写化 + 驼峰/下划线边界切分（驼峰切分在小写化之前进行）
     * - 中文：jieba分词，保留&gt;=2字词
     */
    public static Map<String, Double> tokenize(String text) {
        Map<String, Double> tf = new HashMap<>();
        if (StrUtil.isBlank(text)) return tf;
        List<String> terms = new ArrayList<>();
        // 驼峰/下划线边界切分（需保留原始大小写识别边界）
        Matcher ascii = ASCII_WORD.matcher(text);
        while (ascii.find()) {
            terms.addAll(splitCamelCase(ascii.group()));
        }
        // 整体小写化后按词切分；纯ASCII词直接保留，含中文的段交由jieba
        String cleaned = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5_]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!cleaned.isEmpty()) {
            for (String part : cleaned.split(" ")) {
                if (ASCII_ONLY.matcher(part).matches()) {
                    terms.add(part);
                } else {
                    for (String t : SEGMENTER.sentenceProcess(part)) {
                        String trimmed = t.trim().toLowerCase(Locale.ROOT);
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

    public static List<String> splitCamelCase(String s) {
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
                result.add(current.toString().toLowerCase(Locale.ROOT));
                current = new StringBuilder();
            }
            current.append(Character.toLowerCase(c));
        }
        if (current.length() > 0) result.add(current.toString().toLowerCase(Locale.ROOT));
        return result;
    }

    // ==================== 语义维度（Sem） ====================

    /**
     * GAP-004 升级：向量余弦优先，值域映射 [0,1]；
     * 任一方无向量 -&gt; 降级现有 TF-IDF + jieba + 词典路径（原样保留）。
     */
    public static double calculateCosineSimilarity(Map<String, Double> reqTf, Map<String, Double> codeTf,
                                                   Map<String, Double> idfMap,
                                                   SemanticVector reqVec, SemanticVector codeVec) {
        // P0-2：向量由调用方预解析一次传入（并行阶段零 JSON 反序列化）
        // 向量路径：双端均有稠密向量且维度一致 -> (1+cos)/2 映射到 [0,1]
        if (reqVec != null && codeVec != null
                && reqVec.hasVector() && codeVec.hasVector() && reqVec.getDim() == codeVec.getDim()) {
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
    public static double tfidfCosine(Map<String, Double> reqTf, Map<String, Double> codeTf,
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
     * P0-5：语义校准——对该需求在全部代码单元上的语义分做批内/参数化变换。
     * 说明：此处"原始语义分"即 calculateCosineSimilarity 的线性输出（BGE (1+cos)/2 或 TF-IDF 兜底），
     * SIGMOID/MINMAX 均在其上二次变换；默认 LINEAR 直接透传，与历史行为逐位一致。
     */
    public static double[] calibrateSemantics(List<CodeUnit> codeUnits,
                                              Map<Long, Map<String, Double>> codeVectors,
                                              Map<Long, SemanticVector> codeSemVectors,
                                              Map<String, Double> idfMap,
                                              Map<String, Double> reqVec,
                                              SemanticVector reqSemVec,
                                              ConsistencyChecker.SemanticCalibration calibration) {
        int n = codeUnits.size();
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            CodeUnit code = codeUnits.get(i);
            raw[i] = calculateCosineSimilarity(reqVec,
                    codeVectors.getOrDefault(code.getId(), Collections.emptyMap()), idfMap,
                    reqSemVec, codeSemVectors.get(code.getId()));
        }
        ConsistencyChecker.SemanticCalibration cal = calibration;
        if (cal == null || cal.getMode() == ConsistencyChecker.SemanticCalibration.Mode.LINEAR) {
            return raw;
        }
        if (cal.getMode() == ConsistencyChecker.SemanticCalibration.Mode.SIGMOID) {
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = sigmoidMap(raw[i], cal.getCenter(), cal.getWidth());
            }
            return out;
        }
        // MINMAX：批内最小-最大归一化，把需求自身的相对匹配度拉开到 [0,1]；
        // 全批跨度过小（无区分信息）时回退原值，避免数值放大噪声。
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : raw) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double span = max - min;
        if (span < 1e-3) {
            return raw;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = (raw[i] - min) / span;
        }
        return out;
    }

    /** sigmoid 温度缩放（center 为中心、width 为温度；防溢出） */
    public static double sigmoidMap(double x, double center, double width) {
        double w = width <= 0 ? 0.05 : width;
        double z = (x - center) / w;
        if (z > 30) return 1.0;
        if (z < -30) return 0.0;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    // ==================== 约束维度（Con） ====================

    /**
     * GAP-005 + GAP-046：Con 维度——规约约束 vs 代码实现证据覆盖度。
     * GAP-046 主判据：统一采用 RequirementConstraintExtractor.constraintMatch（需求约束点 × 代码实现证据
     * 的结构化比对），替代原中性底分 calculateSimplifiedConstraintMatch，解决 Con 维度近常量无判别力的根因
     * （AUD-02）。仅在需求/代码为空等退化场景下回退中性底分。
     */
    public static double calculateConstraintMatch(Requirement req, CodeUnit code,
                                                  RequirementConstraintExtractor.CodeProfile codeProfile) {
        try {
            String reqDoc = reqDoc(req);
            String codeContent = code.getCodeContent();
            if (StrUtil.isBlank(reqDoc) || StrUtil.isBlank(codeContent)) {
                return calculateSimplifiedConstraintMatch(req, code, codeProfile);
            }
            // GAP-046：约束点 × 代码实现证据的结构化比对（P0-1：代码侧证据已预扫描至 CodeProfile，配对内零全文正则扫描）
            double cm = RequirementConstraintExtractor.constraintMatchProfiled(reqDoc, codeProfile);
            if (cm < 0) {
                // FUN-13：需求无可提取约束点（英文/无约束语义）→ 回退中性启发式，避免被高估/低估主导匹配
                return calculateSimplifiedConstraintMatch(req, code, codeProfile);
            }
            return clamp01(cm);
        } catch (Exception e) {
            log.error("计算约束匹配度失败: req={}, code={}", req.getRequirementId(),
                    code.getClassName() + "." + code.getMethodName(), e);
            return calculateSimplifiedConstraintMatch(req, code, codeProfile);
        }
    }

    /**
     * GAP-046：约束匹配度（回退逻辑）——由「需求约束点 × 代码实现证据」覆盖度驱动，
     * 替代近常量启发式，使「需求要求约束但代码未实现」的缺陷对 Con 显著下降。
     * 需求含可计分约束点时走证据集结构化比对；无约束点（extract 为空）或异常时回退中性 0.5（FUN-08②）。
     */
    public static double calculateSimplifiedConstraintMatch(Requirement req, CodeUnit code,
                                                            RequirementConstraintExtractor.CodeProfile codeProfile) {
        try {
            String reqText = req.getOriginalText();
            double coverage = RequirementConstraintExtractor.constraintMatchProfiled(reqText, codeProfile);
            if (coverage >= 0) {
                // 覆盖度计分基础上保留少量中性底分，避免纯结构比对将合法实现压得过低
                return clamp01(0.35 + 0.65 * coverage);
            }
        } catch (Exception e) {
            log.warn("需求约束点提取失败，回退中性约束匹配: {}", e.getMessage());
        }
        // FUN-08②：无约束点需求（extract 为空）无法评估约束实现——回退中性 0.5，与 Inv 对称（FUN-13 同口径）。
        return 0.5;
    }

    // ==================== 不变量维度（Inv） ====================

    /**
     * GAP-025：不变量满足度计算（支持 CFG 特征缓存）。
     * 同一代码单元的 cfgData 在 R×C 配对中重复使用，CFG 特征只需提取一次。
     */
    public static double calculateInvariantSatisfactionWithCache(Requirement req, CodeUnit code,
                                                                 FormalSpecParserUtil.SpecModel specModel,
                                                                 String cfgData,
                                                                 Map<Long, CfgFeatures> cfgFeaturesCache,
                                                                 RequirementConstraintExtractor.CodeProfile codeProfile) {
        try {
            if (specModel == null || specModel.getInvariants().isEmpty() || StrUtil.isBlank(cfgData)) {
                return calculateSimplifiedInvariantSatisfaction(req, code, codeProfile);
            }
            // GAP-025：从缓存获取 CFG 特征（避免重复解析 JSON）
            CfgFeatures cf = cfgFeaturesCache.computeIfAbsent(code.getId(),
                    cid -> extractCfgFeatures(cfgData));
            if (cf == null) {
                return calculateSimplifiedInvariantSatisfaction(req, code, codeProfile);
            }
            // 规约侧结构要求：状态转移谓词/多状态 -> 需要分支结构；转移(all =>) -> 需要循环；异常约束 -> 需要异常路径
            boolean specRequiresBranch = specModel.getInvariants().stream()
                    .anyMatch(FormalSpecParserUtil.InvariantClause::isHasTransition)
                    || specModel.getInvariants().stream().anyMatch(i -> i.getStates().size() > 1);
            boolean specRequiresLoop = specModel.getInvariants().stream().anyMatch(
                    i -> i.getClauseText().toLowerCase(Locale.ROOT).contains("all ") && i.getClauseText().contains("=>"));
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
            double apSat = calculateAtomicPropositionSatisfaction(req, code, codeProfile);
            if (items == 0) {
                // 规格无结构项可判时，优先以 AP 满足度作为 Inv（AP 缺失则回退 simplified）
                return apSat >= 0 ? clamp01(apSat) : calculateSimplifiedInvariantSatisfaction(req, code, codeProfile);
            }
            if (apSat >= 0) { sum += apSat; items++; }
            return clamp01(sum / items);
        } catch (Exception e) {
            log.error("计算不变量满足度失败: req={}, code={}", req.getRequirementId(),
                    code.getClassName() + "." + code.getMethodName(), e);
            return calculateSimplifiedInvariantSatisfaction(req, code, codeProfile);
        }
    }

    /**
     * GAP-046：简化的不变量满足度（回退逻辑）——由「需求约束点 × 代码实现证据」覆盖度驱动。
     * 需求含约束点时代码未实现对应证据则 Inv 下降；无约束点回退结构启发式。
     */
    public static double calculateSimplifiedInvariantSatisfaction(Requirement req, CodeUnit code,
                                                                  RequirementConstraintExtractor.CodeProfile codeProfile) {
        try {
            String reqText = req.getOriginalText();
            double coverage = RequirementConstraintExtractor.constraintMatchProfiled(reqText, codeProfile);
            if (coverage >= 0) {
                // 不变量满足度与约束实现呈正相关：约束覆盖率低 -> 不变量满足度低
                return clamp01(0.5 + 0.5 * coverage);
            }
        } catch (Exception e) {
            log.warn("需求约束点提取失败，回退不变量启发式: {}", e.getMessage());
        }
        // FUN-13：无约束点需求不变量满足度取中性 0.5。
        return 0.5;
    }

    /**
     * GAP-045：原子命题(AP)满足度——需求状态标签 L(s) 中声明的可判真伪命题，
     * 与代码实现内容的约束匹配均值 [0,1]；无 AP 或解析失败返回 -1（跳过）。
     * 使 Kripke 模型中"需求显式命题"真正参与 Inv 维度判定。
     */
    public static double calculateAtomicPropositionSatisfaction(Requirement req, CodeUnit code,
                                                                RequirementConstraintExtractor.CodeProfile codeProfile) {
        String apField = req.getAtomicPropositions();
        if (StrUtil.isBlank(apField) || "[]".equals(apField.trim())) {
            return -1;
        }
        String codeText = code.getCodeContent() != null ? code.getCodeContent() : "";
        if (codeText.isEmpty()) {
            return -1;
        }
        // 原子命题以逗号分隔；每个命题作为约束点，与代码做约束匹配（P0-1：复用代码侧预扫描证据）
        String[] props = apField.split(",");
        double sum = 0;
        int n = 0;
        for (String p : props) {
            String ap = p.trim();
            if (ap.isEmpty()) continue;
            // 含比较符(≥/≤/=)的命题：检查代码是否体现该约束主题
            double m = RequirementConstraintExtractor.constraintMatchProfiled(ap, codeProfile);
            if (m >= 0) {
                sum += clamp01(m);
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    // ==================== CFG 结构特征 ====================

    /**
     * CFG 结构特征（GAP-005 / P1-3 扩展，从 CodeUnit.cfgData JSON 提取）。
     * JSON 同构格式：{"nodes":[{"id,type,label,line"}],"edges":[{"from,to,label"}]}
     * （Soot 字节码级 SootCfgBuilderUtil 与 AST 级 CfgBuilderUtil 共用；节点 type 见两构建器注释）。
     */
    public static class CfgFeatures {
        // —— P1-3 新增的结构化统计 ——
        public int nodeCount;
        public int edgeCount;
        /** 分支节点数（if/switch/goto/loop/loop_exit 均为多出口结构） */
        public int branchCount;
        /** 循环节点数（AST 级 type=loop；字节码级由回边个数近似） */
        public int loopCount;
        public int returnCount;
        public int throwCount;
        /** 环复杂度 McCabe：E - N + 2（至少 1），反映方法控制流复杂度 */
        public int cyclomaticComplexity;
        // —— 既有布尔特征（保留，供 Inv 严格路径使用）——
        public boolean branchPresent;
        public boolean loopPresent;
        public int exceptionEdges;
    }

    /** 从 cfgData JSON（Soot 字节码级或 AST 级同构格式）提取结构特征；解析失败返回 null（触发降级） */
    public static CfgFeatures extractCfgFeatures(String cfgData) {
        try {
            cn.hutool.json.JSONObject cfg = JSONUtil.parseObj(cfgData);
            cn.hutool.json.JSONArray nodes = cfg.getJSONArray("nodes");
            cn.hutool.json.JSONArray edges = cfg.getJSONArray("edges");
            CfgFeatures f = new CfgFeatures();
            java.util.Map<Integer, Integer> idIndex = new HashMap<>();
            if (nodes != null) {
                f.nodeCount = nodes.size();
                int i = 0;
                for (Object o : nodes) {
                    cn.hutool.json.JSONObject n = (cn.hutool.json.JSONObject) o;
                    idIndex.put(n.getInt("id"), i++);
                    String type = n.getStr("type", "");
                    if ("if".equals(type) || "switch".equals(type) || "goto".equals(type)) {
                        f.branchPresent = true;
                        f.branchCount++;
                    } else if ("loop".equals(type)) {
                        f.loopCount++;
                    } else if ("return".equals(type)) {
                        f.returnCount++;
                    } else if ("throw".equals(type)) {
                        f.throwCount++;
                    }
                }
            }
            if (edges != null) {
                f.edgeCount = edges.size();
                int backEdges = 0;
                for (Object o : edges) {
                    cn.hutool.json.JSONObject e = (cn.hutool.json.JSONObject) o;
                    if ("catch".equals(e.getStr("label", ""))) {
                        f.exceptionEdges++;
                        continue;
                    }
                    Integer fi = idIndex.get(e.getInt("from"));
                    Integer ti = idIndex.get(e.getInt("to"));
                    // 回边（goto/循环结构）：目标 id <= 源 id（start=0 在前，向后跳转即回边）
                    if (fi != null && ti != null && ti <= fi) {
                        f.loopPresent = true;
                        backEdges++;
                    }
                }
                // 字节码级（Soot）无 type=loop 节点，用回边数近似循环节点数；AST 级节点已计入则不重复加
                f.loopCount = Math.max(f.loopCount, backEdges);
            }
            f.cyclomaticComplexity = Math.max(1, f.edgeCount - f.nodeCount + 2);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 通用 ====================

    public static double clamp01(double v) {
        return Math.min(1.0, Math.max(0.0, v));
    }
}
