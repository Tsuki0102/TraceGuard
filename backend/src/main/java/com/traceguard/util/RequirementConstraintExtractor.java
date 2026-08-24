package com.traceguard.util;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RequirementConstraintExtractor - GAP-046 增强版
 *
 * 在原有启发式约束匹配基础上，增加精确约束比对惩罚机制：
 *   1. 数值阈值比对：需求中的具体数字与代码中的常量/阈值不一致时施加惩罚
 *   2. 必填参数校验覆盖：需求提到的必填参数未在代码中校验时施加惩罚
 *   3. 状态值精确匹配：需求中的状态词未在代码中出现时施加惩罚
 *   4. 关键概念完整性：需求核心概念（如 reserved、幂等窗口）缺失时施加惩罚
 *   5. 代码异常模式：空 catch、数组越界风险、比较器缺陷等施加惩罚
 *
 * 规则模式是 TraceGuard 默认基线，LLM 仅作为增强演示。
 */
public class RequirementConstraintExtractor {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(RequirementConstraintExtractor.class);

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    // ========== 约束点定义（保持兼容） ==========

    public enum Kind {
        NON_NULL,           // 非空/必填校验
        NUMERIC_THRESHOLD,  // 数值范围/阈值校验
        STATE_GUARD,        // 状态前置校验
        EXCEPTION_PATH,     // 异常/错误路径
        RESOURCE_RELEASE,   // 资源释放
        UNIQUENESS,         // 唯一性校验
        RANGE,              // 范围校验
        STOCK_CHECK,        // 库存检查
        IDEMPOTENT,         // 幂等性
        RATE_LIMIT,         // 限流
        PARAM_VALID,        // 参数合法性
        BUSINESS_RULE,      // 业务规则
        UNKNOWN
    }

    public static class ConstraintPoint {
        private final Kind kind;
        private final String snippet;
        private final int position;

        public ConstraintPoint(Kind kind, String snippet, int position) {
            this.kind = kind;
            this.snippet = snippet;
            this.position = position;
        }

        public Kind getKind() { return kind; }
        public String getSnippet() { return snippet; }
        public int getPosition() { return position; }

        @Override
        public String toString() {
            return kind + ":" + snippet;
        }
    }

    // ========== 关键词表 ==========

    private static final Map<Kind, List<String>> KIND_PATTERNS = new LinkedHashMap<>();
    static {
        KIND_PATTERNS.put(Kind.NON_NULL, Arrays.asList(
                "must not be null", "cannot be null", "should not be null",
                "不能为空", "不能为 null", "必须提供", "必填", "必传",
                "非空", "不为空", "not empty", "not null", "is empty", "is null",
                "为空", "为 null"));
        KIND_PATTERNS.put(Kind.NUMERIC_THRESHOLD, Arrays.asList(
                "greater than", "less than", "at least", "at most", "no more than",
                "不超过", "不低于", "至少", "最多", "最少", "大于", "小于", "等于",
                "> 0", ">0", ">="));
        KIND_PATTERNS.put(Kind.STATE_GUARD, Arrays.asList(
                "must be in", "status must", "state must", "只有.*才能", "才能.*",
                "必须.*状态", "状态.*才能", "状态为", "status",
                // 通用状态词：识别"订单状态需在状态域内""状态转换""state"等状态约束需求
                "状态域", "状态转换", "状态字段", "状态枚举", "状态取值",
                "状态", "state"));
        KIND_PATTERNS.put(Kind.EXCEPTION_PATH, Arrays.asList(
                "throw", "exception", "error", "失败", "异常", "错误"));
        KIND_PATTERNS.put(Kind.RESOURCE_RELEASE, Arrays.asList(
                "release", "close", "unlock", "free", "释放", "关闭", "解锁"));
        KIND_PATTERNS.put(Kind.UNIQUENESS, Arrays.asList(
                "unique", "distinct", "only", "唯一", "重复"));
        KIND_PATTERNS.put(Kind.RANGE, Arrays.asList(
                "between", "range", "in.*range", "范围内", "区间", "范围"));
        KIND_PATTERNS.put(Kind.STOCK_CHECK, Arrays.asList(
                "stock", "inventory", "库存"));
        KIND_PATTERNS.put(Kind.IDEMPOTENT, Arrays.asList(
                "idempotent", "幂等", "重复提交", "重复请求"));
        KIND_PATTERNS.put(Kind.RATE_LIMIT, Arrays.asList(
                "rate limit", "throttle", "限流", "频率", "每分钟", "每秒"));
        KIND_PATTERNS.put(Kind.PARAM_VALID, Arrays.asList(
                "validate", "valid", "校验", "验证", "合法性", "合法"));
        // FUN-13：BUSINESS_RULE 仅保留中文义务词。英文情态动词（must/should/shall）过于泛化——
        // 任何英文需求都会命中并提取出"implemented 恒真"的约束点（keywordCoverage(code,code)>0.3 恒成立），
        // 使无关联英文需求对所有代码 con=1.0，导致跨语言匹配排序错乱。英文精准约束由 NON_NULL/PARAM_VALID 等处理
        KIND_PATTERNS.put(Kind.BUSINESS_RULE, Arrays.asList(
                "必须", "应该", "应当"));
    }

    // ========== 公共 API ==========

    public static List<ConstraintPoint> extract(String requirementText) {
        List<ConstraintPoint> points = new ArrayList<>();
        if (requirementText == null || requirementText.trim().isEmpty()) {
            return points;
        }
        String lower = requirementText.toLowerCase(Locale.ROOT);
        for (Map.Entry<Kind, List<String>> entry : KIND_PATTERNS.entrySet()) {
            for (String pat : entry.getValue()) {
                Pattern p = Pattern.compile(pat.toLowerCase(Locale.ROOT));
                Matcher m = p.matcher(lower);
                while (m.find()) {
                    int start = Math.max(0, m.start() - 20);
                    int end = Math.min(requirementText.length(), m.end() + 30);
                    points.add(new ConstraintPoint(entry.getKey(),
                            requirementText.substring(start, end).trim(), m.start()));
                }
            }
        }
        return points.stream()
                .sorted(Comparator.comparingInt(ConstraintPoint::getPosition))
                .collect(Collectors.toList());
    }

    /**
     * 计算约束匹配度 [0,1]。
     * 基础分采用启发式关键词匹配，再叠加精确约束不匹配的惩罚项。
     */
    public static double constraintMatch(String requirementText, String codeContent) {
        if (requirementText == null || codeContent == null) {
            return 0.0;
        }
        List<ConstraintPoint> points = extract(requirementText);
        double base = legacyConstraintMatch(points, codeContent);
        if (points.isEmpty()) {
            // FUN-13：无约束点需求（多为英文/无明确约束语义）无法评估约束匹配，返回 -1 由调用方回退中性值；
            // 原逻辑用 keywordCoverage 会把英文需求对所有代码高估为全覆盖（con=1.0），导致跨语言匹配排序错乱
            return -1;
        }
        double penalty = computeMismatchPenalty(requirementText, codeContent, points);
        // 临时诊断
        if (System.getProperty("gap046.debug") != null) {
            System.out.println("[GAP046-RC] req=" + requirementText.substring(0, Math.min(30, requirementText.length()))
                    + " base=" + String.format("%.2f", base)
                    + " penalty=" + String.format("%.2f", penalty)
                    + " pts=" + points.stream().map(p -> p.kind.name()).collect(Collectors.toList()));
        }
        return clamp(base - penalty, 0.0, 1.0);
    }

    public static double invariantSatisfaction(String requirementText, String codeContent) {
        if (requirementText == null || codeContent == null) {
            return 0.0;
        }
        double cfgScore = computeCfgScore(codeContent);
        double keywordScore = keywordCoverage(requirementText, codeContent);
        double anomalyPenalty = detectAnomalyPenalty(requirementText, codeContent);
        double raw = 0.5 * cfgScore + 0.5 * keywordScore - anomalyPenalty;
        return clamp(raw, 0.0, 1.0);
    }

    public static String determineDefectType(double sem, double con, double inv,
                                             double total, boolean defective) {
        if (!defective) {
            return "一致";
        }
        if (con < 0.45 && inv < 0.5) {
            return "需求缺失/未实现";
        }
        if (con < 0.55) {
            return "约束条件不满足";
        }
        if (inv < 0.55) {
            return "不变量违反";
        }
        if (sem < 0.35) {
            return "语义不匹配";
        }
        return "业务逻辑不一致";
    }

    public static String detectedMainType(double sem, double con, double inv,
                                          double total, boolean defective) {
        return determineDefectType(sem, con, inv, total, defective);
    }

    /**
     * 保持兼容：判断某类约束点是否在代码中被实现。
     */
    public static boolean implemented(Kind kind, String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        switch (kind) {
            case NON_NULL:
                return lower.contains("null") || lower.contains("empty") || lower.contains("==");
            case NUMERIC_THRESHOLD:
                return Pattern.compile("[<>=]+|\\.compareto|\\.(max|min)", Pattern.CASE_INSENSITIVE)
                        .matcher(code).find();
            case STATE_GUARD:
                return (lower.contains("status") || lower.contains("state"))
                        && (lower.contains("equals") || lower.contains("==") || lower.contains("!="));
            case EXCEPTION_PATH:
                return lower.contains("throw") || lower.contains("exception")
                        || lower.contains("error") || lower.contains("return false");
            case RESOURCE_RELEASE:
                return lower.contains("close") || lower.contains("release")
                        || lower.contains("unlock") || lower.contains("finally");
            case UNIQUENESS:
                return lower.contains("unique") || lower.contains("distinct")
                        || lower.contains("contains") || lower.contains("duplicate");
            case RANGE:
                return lower.contains("<") || lower.contains(">")
                        || lower.contains("between") || lower.contains("range");
            case STOCK_CHECK:
                return lower.contains("stock") || lower.contains("inventory")
                        || lower.contains("quantity") || lower.contains("库存");
            case IDEMPOTENT:
                return lower.contains("idempot") || lower.contains("requestid")
                        || lower.contains("timestamp") || lower.contains("duration")
                        || lower.contains("window") || lower.contains("seconds")
                        || lower.contains("幂等") || lower.contains("去重");
            case RATE_LIMIT:
                return lower.contains("rate") || lower.contains("limit")
                        || lower.contains("throttle") || lower.contains("限流");
            case PARAM_VALID:
                return lower.contains("valid") || lower.contains("check")
                        || lower.contains("校验") || lower.contains("验证");
            case BUSINESS_RULE:
            default:
                return keywordCoverage(code, code) > 0.3;
        }
    }

    // ========== 内部实现 ==========

    private static double legacyConstraintMatch(List<ConstraintPoint> points, String code) {
        if (points.isEmpty()) return 0.5;
        double total = 0;
        for (ConstraintPoint p : points) {
            total += implemented(p.getKind(), code) ? 1.0 : 0.0;
        }
        return clamp(total / points.size(), 0.0, 1.0);
    }

    /**
     * 计算精确约束不匹配惩罚 [0, ~1.2]。
     */
    private static double computeMismatchPenalty(String req, String code, List<ConstraintPoint> points) {
        double penalty = 0.0;
        Set<Kind> kinds = points.stream().map(ConstraintPoint::getKind).collect(Collectors.toSet());

        if (kinds.contains(Kind.NUMERIC_THRESHOLD)) {
            double np = numericMismatchPenalty(req, code);
            if (np > 0) penalty += np;
        }
        if (kinds.contains(Kind.NON_NULL) || kinds.contains(Kind.PARAM_VALID)) {
            double pp = paramValidationMismatchPenalty(req, code);
            if (pp > 0) penalty += pp;
        }
        if (kinds.contains(Kind.STATE_GUARD)) {
            double sp = stateValueMismatchPenalty(req, code);
            if (sp > 0) penalty += sp;
        }
        if (kinds.contains(Kind.STOCK_CHECK)) {
            double kp = keywordMissingPenalty(code, Arrays.asList("reserved", "freeze", "frozen", "占用", "冻结", "预留"));
            if (kp > 0) penalty += kp;
        }
        if (kinds.contains(Kind.IDEMPOTENT)) {
            double ip = keywordMissingPenalty(code, Arrays.asList("duration", "seconds", "window", "timestamp", "interval"));
            if (ip > 0) penalty += ip;
        }

        return clamp(penalty, 0.0, 0.45);
    }

    private static double numericMismatchPenalty(String req, String code) {
        List<Integer> reqNumbers = extractNumbers(req);
        List<Integer> codeNumbers = extractNumbers(code);
        if (reqNumbers.isEmpty() || codeNumbers.isEmpty()) {
            return 0.0;
        }
        // 需求中的关键阈值通常是最小值（如最多 10 条、60 秒）
        int reqPrimary = reqNumbers.stream().min(Integer::compare).orElse(0);
        int codePrimary = codeNumbers.stream().min(Integer::compare).orElse(0);
        if (reqPrimary > 0 && codePrimary > 0 && reqPrimary != codePrimary) {
            double ratio = (double) Math.min(reqPrimary, codePrimary) / Math.max(reqPrimary, codePrimary);
            // 差异越大惩罚越大，最高 0.45
            return 0.45 * (1.0 - ratio);
        }
        return 0.0;
    }

    private static double paramValidationMismatchPenalty(String req, String code) {
        List<String> params = extractCandidateParamNames(req);
        if (params.isEmpty()) return 0.0;
        int validated = 0;
        for (String param : params) {
            if (hasParamValidation(code, param)) validated++;
        }
        double coverage = (double) validated / params.size();
        // 只有覆盖率极低时才给惩罚，避免正常代码被误伤
        if (coverage >= 0.66) return 0.0;
        if (coverage >= 0.33) return 0.20;
        return 0.40;
    }

    private static double stateValueMismatchPenalty(String req, String code) {
        List<String> states = extractStateValues(req);
        if (states.isEmpty()) return 0.0;
        String codeLower = code.toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String s : states) {
            if (codeLower.contains(s.toLowerCase(Locale.ROOT))) matched++;
        }
        double ratio = (double) matched / states.size();
        if (ratio >= 0.5) return 0.0;
        // 需求要求状态 A，代码中完全没出现 A，很可能是状态错误
        return 0.45;
    }

    private static double keywordMissingPenalty(String code, List<String> keywords) {
        String codeLower = code.toLowerCase(Locale.ROOT);
        boolean any = keywords.stream().anyMatch(k -> codeLower.contains(k.toLowerCase(Locale.ROOT)));
        return any ? 0.0 : 0.35;
    }

    private static double keywordCoverage(String req, String code) {
        Set<String> reqWords = meaningfulWords(req);
        if (reqWords.isEmpty()) return 0.5;
        Set<String> codeWords = meaningfulWords(code);
        long matched = reqWords.stream().filter(codeWords::contains).count();
        return clamp((double) matched / reqWords.size(), 0.0, 1.0);
    }

    private static double computeCfgScore(String code) {
        double score = 0.5;
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.contains("if")) score += 0.1;
        if (lower.contains("throw") || lower.contains("exception")) score += 0.1;
        if (lower.contains("null")) score += 0.1;
        if (lower.contains("try") && lower.contains("finally")) score += 0.1;
        if (lower.contains("for") || lower.contains("while")) score += 0.05;
        return clamp(score, 0.0, 1.0);
    }

    private static double detectAnomalyPenalty(String req, String code) {
        double penalty = 0.0;
        String codeLower = code.toLowerCase(Locale.ROOT);

        // 数组越界风险：.get(size) 而不是 .get(size()-1)
        if (Pattern.compile("\\.get\\(\\s*[a-z]+\\.size\\(\\s*\\)\\s*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(code).find()) {
            penalty += 0.35;
        }
        // 比较器反身性缺陷：相等时返回非 0
        if (Pattern.compile("return\\s+1\\s*;", Pattern.CASE_INSENSITIVE).matcher(code).find()
                && (codeLower.contains("comparator") || codeLower.contains("sort"))) {
            penalty += 0.25;
        }
        // 空 catch 块
        if (Pattern.compile("catch\\s*\\([^\\)]*\\)\\s*\\{\\s*\\}", Pattern.CASE_INSENSITIVE)
                .matcher(code).find()) {
            penalty += 0.25;
        }
        // 需求要求状态 A，代码中完全未出现
        List<String> reqStates = extractStateValues(req);
        if (!reqStates.isEmpty()) {
            boolean anyMatched = reqStates.stream()
                    .anyMatch(s -> codeLower.contains(s.toLowerCase(Locale.ROOT)));
            if (!anyMatched) {
                penalty += 0.20;
            }
        }
        return clamp(penalty, 0.0, 0.6);
    }

    // ========== 文本/数值提取工具 ==========

    private static List<Integer> extractNumbers(String text) {
        List<Integer> nums = new ArrayList<>();
        if (text == null) return nums;
        Matcher m = Pattern.compile("\\b(\\d{1,4})\\b").matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n > 0 && n < 100000) {
                nums.add(n);
            }
        }
        return nums;
    }

    private static List<String> extractCandidateParamNames(String req) {
        List<String> params = new ArrayList<>();
        if (req == null) return params;
        Map<String, String[]> mappings = new LinkedHashMap<>();
        mappings.put("userId", new String[]{"用户id", "用户 id", "用户标识", "用户ID", "userId", "user id"});
        mappings.put("channel", new String[]{"渠道类型", "渠道", "channel"});
        mappings.put("content", new String[]{"通知内容", "内容", "content"});
        mappings.put("duration", new String[]{"考试时长", "时长", "duration"});
        mappings.put("passingScore", new String[]{"及格分数", "及格线", "passingscore", "passing score"});
        mappings.put("title", new String[]{"考试标题", "标题", "title"});
        mappings.put("amount", new String[]{"金额", "amount"});
        mappings.put("quantity", new String[]{"数量", "quantity"});
        mappings.put("orderId", new String[]{"订单id", "订单编号", "orderid", "order id"});
        mappings.put("studentId", new String[]{"学生id", "学生标识", "studentid", "student id"});

        String reqLower = req.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : mappings.entrySet()) {
            for (String key : e.getValue()) {
                if (reqLower.contains(key.toLowerCase(Locale.ROOT))) {
                    params.add(e.getKey());
                    break;
                }
            }
        }
        return params;
    }

    private static boolean hasParamValidation(String code, String param) {
        String lower = code.toLowerCase(Locale.ROOT);
        String p = param.toLowerCase(Locale.ROOT);
        String[] patterns = {
                p + "\\s*==\\s*null",
                p + "\\s*!=\\s*null",
                "null\\s*==\\s*" + p,
                "null\\s*!=\\s*" + p,
                "\\b" + p + "\\.isempty\\(\\)",
                "\\b" + p + "\\.isblank\\(\\)",
                "\\b" + p + "\\.length\\(\\)\\s*[<>]=?\\s*0",
                "strings\\.isempty\\(\\s*" + p + "\\s*\\)",
        };
        for (String pt : patterns) {
            if (Pattern.compile(pt, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> extractStateValues(String req) {
        List<String> states = new ArrayList<>();
        if (req == null) return states;
        String[] candidates = {"PENDING", "PAID", "UNPUBLISHED", "PUBLISHED",
                "ACTIVE", "INACTIVE", "SUCCESS", "FAILED", "COMPLETED"};
        String reqLower = req.toLowerCase(Locale.ROOT);
        for (String s : candidates) {
            if (reqLower.contains(s.toLowerCase(Locale.ROOT))) {
                states.add(s);
            }
        }
        Map<String, String> cnStates = new LinkedHashMap<>();
        cnStates.put("未发布", "UNPUBLISHED");
        cnStates.put("已发布", "PUBLISHED");
        cnStates.put("待支付", "PENDING");
        cnStates.put("已支付", "PAID");
        cnStates.put("待处理", "PENDING");
        for (Map.Entry<String, String> e : cnStates.entrySet()) {
            if (req.contains(e.getKey()) && !states.contains(e.getValue())) {
                states.add(e.getValue());
            }
        }
        return states;
    }

    private static Set<String> meaningfulWords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null || text.trim().isEmpty()) return words;
        String lower = text.toLowerCase(Locale.ROOT);
        try {
            List<String> seg = SEGMENTER.sentenceProcess(text).stream()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(w -> w.length() >= 2)
                    .collect(Collectors.toList());
            words.addAll(seg);
        } catch (Exception e) {
            // CQ-04：分词失败不可静默丢弃，记录日志保证可观测（影响语义关键词提取质量）
            LOGGER.warn("需求约束关键词分词失败，回退字母序列提取: {}", e.getMessage());
        }
        Matcher m = Pattern.compile("[a-z]+").matcher(lower);
        while (m.find()) {
            String w = m.group();
            if (w.length() >= 2 && !isStopWord(w)) {
                words.add(w);
            }
        }
        return words;
    }

    private static boolean isStopWord(String w) {
        Set<String> stop = new HashSet<>(Arrays.asList(
                "the", "and", "for", "are", "but", "not", "you", "all", "can",
                "had", "her", "was", "one", "our", "out", "day", "get", "has",
                "him", "his", "how", "its", "may", "new", "now", "old", "see",
                "two", "who", "boy", "did", "she", "use", "her", "way", "many",
                "some", "time", "very", "when", "come", "here", "just", "like",
                "long", "make", "over", "such", "take", "than", "them", "well",
                "were", "will", "with", "have", "from", "they", "know", "want",
                "been", "good", "much", "only", "said", "each", "which", "their",
                "would", "there", "could", "other", "after", "first", "never",
                "these", "think", "where", "being", "every", "great", "might",
                "shall", "still", "those", "under", "while", "this", "that", "into",
                "public", "private", "static", "void", "return", "final", "class",
                "import", "package", "null", "true", "false", "int", "string", "map",
                "list", "new", "if", "else", "for", "while", "try", "catch"));
        return stop.contains(w);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
