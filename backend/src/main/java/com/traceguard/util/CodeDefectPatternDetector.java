package com.traceguard.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码缺陷模式检测器（GAP-046 规则增强）。
 *
 * 仅依赖需求文本与代码文本，检测常见低层/行为级缺陷模式，返回 [0,1] 的风险分。
 * 该分数用于在 ConsistencyChecker 中对综合相似度进行缺陷惩罚，使规则模式在
 * LLM 关闭时也能识别业务逻辑不一致、数值错误、状态错误、参数缺失等问题。
 */
public class CodeDefectPatternDetector {

    /**
     * P1-4：子信号权重（信号名 -> 权重）。
     * 默认配置（2026-09-02 网格 -Drisk.grid=true 实证，M=55）：
     *   1) 剔除两个噪声信号（一致对命中率反超缺陷对）：
     *        stateMismatch 0.0%（缺陷）vs 6.9%（一致）；impliedBusinessRuleMissing 3.8% vs 6.9%
     *   2) 放大一致对命中率为 0 的最安全强信号（只多抓缺陷、不漏抓一致对）：
     *        numericMismatch ×2.0（缺陷 19.2% / 一致 0%）、quantitativeBoundMismatch ×1.5（7.7% / 0%）
     * 网格对照：本配置规则链路准确率 63.6%→65.5%、漏检 50.0%→46.2%，误报保持 24.1%。
     * 注：权重基于 55 对小样本实证标定，扩充评测集后应重跑 -Drisk.grid=true 复核。
     */
    private static volatile Map<String, Double> riskWeights = defaultWeights();

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> m = new HashMap<>();
        m.put("stateMismatch", 0.0);
        m.put("impliedBusinessRuleMissing", 0.0);
        m.put("numericMismatch", 2.0);
        m.put("quantitativeBoundMismatch", 1.5);
        return m;
    }

    /** 复位默认去噪权重；传入非空 Map 可覆盖（eval/管理台调优用）。 */
    public static void configureRiskWeights(Map<String, Double> weights) {
        riskWeights = weights == null || weights.isEmpty() ? defaultWeights() : new HashMap<>(weights);
    }

    /** P1-4：全部已知子信号名（管理台/报告全量展示；未配置信号权重按 1.0 计） */
    public static List<String> riskSignalNames() {
        return Arrays.asList(
                "stateMismatch", "numericMismatch", "paramValidationMissing", "logicInversion",
                "commonCodeBug", "impliedBusinessRuleMissing", "nullDereference", "stateFlowViolation",
                "refundFactor", "quantitativeBoundMismatch");
    }

    /** P1-4：当前生效权重快照（全量信号 -> 权重），供管理台展示/持久化回读 */
    public static Map<String, Double> riskSignalWeights() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String s : riskSignalNames()) {
            m.put(s, weightOf(s));
        }
        return m;
    }

    /** P1-4：内置默认权重快照（新增配置未写入任何值时的落库默认） */
    public static Map<String, Double> baselineRiskWeights() {
        return defaultWeights();
    }

    private static double weightOf(String signal) {
        Map<String, Double> w = riskWeights;
        Double d = w == null ? null : w.get(signal);
        return d == null ? 1.0 : d;
    }

    /**
     * 检测需求-代码对的缺陷风险分。
     */
    public static double detectDefectRisk(String requirementText, String codeContent) {
        return detectDefectRisk(requirementText, codeContent, null);
    }

    /**
     * 检测缺陷风险分（带类级证据上下文，FUN-04b）。
     *
     * @param classEvidence 类级常量定义列表（形如 "private static final int X = 30;"），
     *                      用于跨方法的量化常量核对；null/空时相关信号不生效
     */
    public static double detectDefectRisk(String requirementText, String codeContent,
                                          List<String> classEvidence) {
        if (requirementText == null || codeContent == null) {
            return 0.0;
        }
        String req = requirementText;
        String code = codeContent;
        double risk = 0.0;

        // P1-4：加权累加（默认权重全 1 = 历史行为；configureRiskWeights 可剔除噪声/放大强信号）
        risk += weightOf("stateMismatch") * stateMismatchRisk(req, code);
        risk += weightOf("numericMismatch") * numericMismatchRisk(req, code);
        risk += weightOf("paramValidationMissing") * paramValidationMissingRisk(req, code);
        risk += weightOf("logicInversion") * logicInversionRisk(req, code);
        risk += weightOf("commonCodeBug") * commonCodeBugRisk(code);
        risk += weightOf("impliedBusinessRuleMissing") * impliedBusinessRuleMissingRisk(req, code);
        // FUN-04 规则链路误报治理（2026-08-27 增强）
        risk += weightOf("nullDereference") * nullDereferenceRisk(code);          // NPE/空解引用（CL-004 类）
        risk += weightOf("stateFlowViolation") * stateFlowViolationRisk(req, code);  // 状态流转顺序违反（CL-018 类）
        risk += weightOf("refundFactor") * refundFactorRisk(req, code);        // 退款系数异常（CL-006 类）
        // FUN-04b：类级量化常量与需求显式数值边界的错配（幂等窗口/限流阈值类，CL-022/023 类）
        risk += weightOf("quantitativeBoundMismatch") * quantitativeBoundMismatchRisk(req, code, classEvidence);

        return clamp(risk, 0.0, 0.65);
    }

    /**
     * 信号分解（可解释性/FUN-04b 诊断）：返回各子信号名称 -> 本对得分，不含钳制。
     * 供前端展示「命中规则」与评测诊断使用，不参与判定本身。
     */
    public static Map<String, Double> explainSignals(String requirementText, String codeContent,
                                                     List<String> classEvidence) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (requirementText == null || codeContent == null) {
            return m;
        }
        m.put("stateMismatch", stateMismatchRisk(requirementText, codeContent));
        m.put("numericMismatch", numericMismatchRisk(requirementText, codeContent));
        m.put("paramValidationMissing", paramValidationMissingRisk(requirementText, codeContent));
        m.put("logicInversion", logicInversionRisk(requirementText, codeContent));
        m.put("commonCodeBug", commonCodeBugRisk(codeContent));
        m.put("impliedBusinessRuleMissing", impliedBusinessRuleMissingRisk(requirementText, codeContent));
        m.put("nullDereference", nullDereferenceRisk(codeContent));
        m.put("stateFlowViolation", stateFlowViolationRisk(requirementText, codeContent));
        m.put("refundFactor", refundFactorRisk(requirementText, codeContent));
        m.put("quantitativeBoundMismatch",
                quantitativeBoundMismatchRisk(requirementText, codeContent, classEvidence));
        return m;
    }

    // ==================== P1-5 性能：代码侧纯信号按单元预扫描 ====================

    /**
     * 纯代码侧信号分量（不含需求），供调用方对同一代码单元只扫描一次后在 R×C 内复用。
     * 返回值与 {@link #detectDefectRisk(String,String,List)} 内部该信号加权值逐位一致。
     */
    public static double commonCodeBugComponent(String codeContent) {
        if (codeContent == null) {
            return 0.0;
        }
        return weightOf("commonCodeBug") * commonCodeBugRisk(codeContent);
    }

    /** 纯代码侧信号分量：NPE/空解引用（同上，按单元预扫描一次） */
    public static double nullDereferenceComponent(String codeContent) {
        if (codeContent == null) {
            return 0.0;
        }
        return weightOf("nullDereference") * nullDereferenceRisk(codeContent);
    }

    /**
     * 带预扫描代码侧分量的缺陷风险合成（P1-5）。
     * commonCodeBugPart / nullDerefPart 由调用方对同一代码单元预扫描一次注入；
     * 与 3-参版本逐位等价（两分量在原合成位置加入，顺序一致），消除 R×C 内对 code 的重复全文扫描。
     */
    public static double detectDefectRisk(String requirementText, String codeContent,
                                          List<String> classEvidence,
                                          double commonCodeBugPart, double nullDerefPart) {
        if (requirementText == null || codeContent == null) {
            return 0.0;
        }
        String req = requirementText;
        String code = codeContent;
        double risk = 0.0;

        risk += weightOf("stateMismatch") * stateMismatchRisk(req, code);
        risk += weightOf("numericMismatch") * numericMismatchRisk(req, code);
        risk += weightOf("paramValidationMissing") * paramValidationMissingRisk(req, code);
        risk += weightOf("logicInversion") * logicInversionRisk(req, code);
        risk += commonCodeBugPart;                        // 原 commonCodeBugRisk（加权）位置保持
        risk += weightOf("impliedBusinessRuleMissing") * impliedBusinessRuleMissingRisk(req, code);
        risk += nullDerefPart;                            // 原 nullDereferenceRisk（加权）位置保持
        risk += weightOf("stateFlowViolation") * stateFlowViolationRisk(req, code);
        risk += weightOf("refundFactor") * refundFactorRisk(req, code);
        risk += weightOf("quantitativeBoundMismatch") * quantitativeBoundMismatchRisk(req, code, classEvidence);

        return clamp(risk, 0.0, 0.65);
    }

    // ========== 需求-代码显式不一致 ==========

    /**
     * 状态值不匹配风险：需求要求状态 A，代码校验/使用状态 B。
     */
    private static double stateMismatchRisk(String req, String code) {
        List<String> reqStates = extractStateValues(req);
        if (reqStates.isEmpty()) return 0.0;
        String codeLower = code.toLowerCase(Locale.ROOT);
        boolean anyMatched = reqStates.stream()
                .anyMatch(s -> codeLower.contains(s.toLowerCase(Locale.ROOT)));
        if (!anyMatched) {
            // 需求要求状态 A，代码中完全未出现 A，且代码中有状态检查 => 状态错误风险高
            if (codeLower.contains("status") || codeLower.contains("state")) {
                return 0.55;
            }
            return 0.25;
        }
        return 0.0;
    }

    /**
     * 数值阈值不匹配风险：需求中的关键数值与代码常量不一致。
     */
    private static double numericMismatchRisk(String req, String code) {
        List<Integer> reqNumbers = extractNumbers(req);
        List<Integer> codeNumbers = extractNumbers(code);
        if (reqNumbers.isEmpty() || codeNumbers.isEmpty()) return 0.0;

        int reqPrimary = reqNumbers.stream().min(Integer::compare).orElse(0);
        int codePrimary = codeNumbers.stream().min(Integer::compare).orElse(0);
        if (reqPrimary <= 0 || codePrimary <= 0) return 0.0;

        if (reqPrimary != codePrimary) {
            double ratio = (double) Math.min(reqPrimary, codePrimary) / Math.max(reqPrimary, codePrimary);
            return 0.45 * (1.0 - ratio);
        }
        return 0.0;
    }

    /**
     * 必填参数校验缺失风险：需求提到多个必填参数，代码只校验了部分。
     */
    private static double paramValidationMissingRisk(String req, String code) {
        List<String> params = extractCandidateParamNames(req);
        if (params.isEmpty()) return 0.0;
        // FUN-04（2026-08-27）：仅当需求显式要求"校验/验证/必须/非空/必填/合法性"时才适用本惩罚。
        // 查询/统计/查看/导出类需求中的业务词（数量/金额/订单ID等）是查询条件而非必填参数，
        // 若一律要求代码做参数校验，会把大量一致对（queryById/getDailyCount/导出等）误判为缺陷。
        if (!(req.contains("校验") || req.contains("验证") || req.contains("必须")
                || req.contains("不能为空") || req.contains("非空") || req.contains("必填")
                || req.contains("合法性") || req.contains("无效") || req.contains("合法"))) {
            return 0.0;
        }
        int validated = 0;
        for (String param : params) {
            if (hasParamValidation(code, param)) validated++;
        }
        double coverage = (double) validated / params.size();
        if (coverage >= 0.75) return 0.0;
        if (coverage >= 0.5) return 0.20;
        if (coverage >= 0.25) return 0.40;
        return 0.60;
    }

    /**
     * 逻辑方向反转风险：需求中的比较方向与代码相反。
     * 例如需求说"elapsed > duration"，代码写"elapsed < duration"。
     */
    private static double logicInversionRisk(String req, String code) {
        String reqLower = req.toLowerCase(Locale.ROOT);
        String codeLower = code.toLowerCase(Locale.ROOT);
        double risk = 0.0;

        // 需求：大于/超过/高于；代码：小于/低于
        if ((reqLower.contains("大于") || reqLower.contains("超过") || reqLower.contains("高于"))
                && (codeLower.contains("<") || codeLower.contains("<= "))) {
            risk += 0.35;
        }
        // 需求：小于/低于；代码：大于
        if ((reqLower.contains("小于") || reqLower.contains("低于"))
                && (codeLower.contains(">") || codeLower.contains(">= "))) {
            risk += 0.35;
        }
        // 需求：等于/为；代码：!=
        if ((reqLower.contains("等于") || reqLower.contains("为"))
                && codeLower.contains("!=") && !reqLower.contains("不等于")) {
            risk += 0.25;
        }
        return risk;
    }

    /**
     * 可配置的隐含业务规则（FUN-09 外置）。
     * 每条规则：需求关键词（逗号分隔，全部命中才触发）→ 代码中应出现的证据词（任一命中即视为已实现），
     * 未实现则累计风险分。默认值内置（对齐原硬编码），可通过
     * {@code traceguard.analysis.business-rule-words} 配置覆盖（JSON，见 BusinessRuleWordConfig）。
     */
    public static final class BusinessRule {
        public String reqKeywords;     // 逗号分隔的需求关键词（全部命中才触发）
        public String[] codeEvidence;  // 代码证据词（任一命中视为已实现）
        public double risk;
        public BusinessRule() {}
        public BusinessRule(String reqKeywords, double risk, String... codeEvidence) {
            this.reqKeywords = reqKeywords;
            this.risk = risk;
            this.codeEvidence = codeEvidence;
        }
    }

    private static volatile List<BusinessRule> BUSINESS_RULES = defaultBusinessRules();

    /** 覆盖业务规则表（Spring 启动时由 BusinessRuleWordConfig 注入配置；空/异常时保留内置默认） */
    public static synchronized void configure(List<BusinessRule> rules) {
        if (rules != null && !rules.isEmpty()) {
            BUSINESS_RULES = rules;
        }
    }

    private static List<BusinessRule> defaultBusinessRules() {
        return Arrays.asList(
                new BusinessRule("库存,充足", 0.30, "reserved", "freeze", "frozen", "预留", "冻结"),
                new BusinessRule("退款,全额", 0.45, "* 0.", "*0.", "fee"),
                new BusinessRule("幂等,重复", 0.25, "timestamp", "window", "duration", "interval")
        );
    }

    /**
     * 隐含业务规则缺失风险：需求中提到核心概念，代码未体现。
     * 规则由可配置业务词表驱动（FUN-09），区分"通用规则"与"领域示例"。
     */
    private static double impliedBusinessRuleMissingRisk(String req, String code) {
        double risk = 0.0;
        for (BusinessRule r : BUSINESS_RULES) {
            if (r.reqKeywords == null || r.reqKeywords.isEmpty()) continue;
            boolean allMatched = true;
            for (String k : r.reqKeywords.split(",")) {
                if (k.trim().isEmpty() || !req.contains(k.trim())) {
                    allMatched = false;
                    break;
                }
            }
            if (!allMatched) continue;
            boolean hasEvidence = false;
            if (r.codeEvidence != null) {
                for (String e : r.codeEvidence) {
                    if (!e.isEmpty() && code.contains(e)) {
                        hasEvidence = true;
                        break;
                    }
                }
            }
            if (!hasEvidence) {
                risk += r.risk;
            }
        }
        return Math.min(risk, 0.6);
    }

    // ========== 通用代码坏味/缺陷 ==========

    private static double commonCodeBugRisk(String code) {
        double risk = 0.0;
        String codeLower = code.toLowerCase(Locale.ROOT);

        // 数组越界风险：list.get(list.size())
        if (Pattern.compile("\\.get\\(\\s*[a-z]+\\.size\\(\\s*\\)\\s*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(code).find()) {
            risk += 0.45;
        }
        // 空 catch 块
        if (Pattern.compile("catch\\s*\\([^\\)]*\\)\\s*\\{\\s*\\}", Pattern.CASE_INSENSITIVE)
                .matcher(code).find()) {
            risk += 0.35;
        }
        // 比较器反身性：相等时返回 1 或 -1
        if (Pattern.compile("return\\s+[1-9\\-]\\s*;", Pattern.CASE_INSENSITIVE).matcher(code).find()
                && (codeLower.contains("comparator") || codeLower.contains("compare"))) {
            risk += 0.35;
        }
        // 浮点数 == 比较
        if (Pattern.compile("(float|double)\\s+\\w+\\s*==\\s*", Pattern.CASE_INSENSITIVE).matcher(code).find()
                || Pattern.compile("\\.\\s*compare\\s*\\([^)]*==[^)]*\\)", Pattern.CASE_INSENSITIVE).matcher(code).find()) {
            risk += 0.25;
        }
        // 方法体极短且直接 return 常量（硬编码）
        if (Pattern.compile("\\{\\s*return\\s+(true|false|null|0|1|-1)\\s*;\\s*\\}", Pattern.CASE_INSENSITIVE)
                .matcher(code).find()) {
            risk += 0.30;
        }
        // 除以常量 0 风险（粗略）
        if (Pattern.compile("/\\s*0\\b", Pattern.CASE_INSENSITIVE).matcher(code).find()) {
            risk += 0.40;
        }
        return Math.min(risk, 0.8);
    }

    // ========== FUN-04 规则链路增强（2026-08-27） ==========

    /**
     * NPE/空解引用风险：`var = xxx.get(...)`（Map.get 等）赋值后，var 被直接解引用（调用方法），
     * 但方法内从未对 var 做 null 检查——缺省判空即存在 NPE 风险。
     * 针对 CL-004（payOrder 缺 null 检查后直接 getStatus）类缺陷。
     */
    private static double nullDereferenceRisk(String code) {
        if (code == null || code.isEmpty()) return 0.0;
        double risk = 0.0;
        // 匹配：var = xxx.get(...);（含多级调用链，如 store.get、map.getOrDefault）
        Matcher m = Pattern.compile("\\b(\\w+)\\s*=\\s*\\w+(?:\\.\\w+)*\\.get(?:OrDefault)?\\([^)]*\\)\\s*;")
                .matcher(code);
        while (m.find()) {
            String var = m.group(1);
            // 方法内存在该变量 null 检查则豁免
            boolean hasNullCheck = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*(==|!=)\\s*null", Pattern.CASE_INSENSITIVE)
                    .matcher(code).find();
            if (hasNullCheck) continue;
            // 赋值之后该变量被解引用（.method）
            String after = code.substring(m.end());
            boolean dereferenced = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.", Pattern.CASE_INSENSITIVE)
                    .matcher(after).find();
            if (dereferenced) {
                risk += 0.35;
                break;
            }
        }
        return Math.min(risk, 0.35);
    }

    /**
     * 状态流转顺序违反风险：需求显式定义状态流转顺序（含"顺序/依次/流转"语义且 ≥3 个状态），
     * 代码出现「STATUS_A.equals(getStatus()) → setStatus(STATUS_B)」且 B 不是 A 的直接后继
     * （跳过了中间状态）→ 约束条件不满足。
     * 针对 CL-018（autoConfirmReceipt 从 PAID 直接跳 COMPLETED，跳过 SHIPPED）类缺陷。
     */
    private static double stateFlowViolationRisk(String req, String code) {
        if (req == null || code == null) return 0.0;
        if (!(req.contains("顺序") || req.contains("依次") || req.contains("流转") || req.contains("状态转换"))) {
            return 0.0;
        }
        List<String> states = extractStateValues(req);
        if (states.size() < 3) return 0.0;
        // 按需求文本中首次出现顺序排序（PENDING -> PAID -> SHIPPED -> COMPLETED）
        final String reqUpper = req.toUpperCase(Locale.ROOT);
        states.sort(Comparator.comparingInt(s -> {
            int idx = reqUpper.indexOf(s);
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));
        Matcher m = Pattern.compile("STATUS_(\\w+)\\s*\\.equals\\([^)]*getStatus\\(\\)\\)[^}]*?setStatus\\([^)]*STATUS_(\\w+)\\)",
                Pattern.DOTALL).matcher(code);
        while (m.find()) {
            int from = states.indexOf(m.group(1));
            int to = states.indexOf(m.group(2));
            // B 存在且非 A 的直接后继（跳过中间状态）即判违反
            if (from >= 0 && to >= 0 && to != from + 1) {
                return 0.4;
            }
        }
        return 0.0;
    }

    /**
     * 退款系数异常风险：需求含"退款"（全额退款语义），代码出现乘以小于 1 的系数（如 * 0.9），
     * 即扣除了需求未定义的手续费/比例。
     * 针对 CL-006（refundOrder 错误扣 10% 手续费）类缺陷。
     */
    private static double refundFactorRisk(String req, String code) {
        if (req == null || code == null) return 0.0;
        if (!req.contains("退款")) return 0.0;
        if (Pattern.compile("\\*\\s*0\\.\\d+", Pattern.CASE_INSENSITIVE).matcher(code).find()) {
            return 0.35;
        }
        return 0.0;
    }

    // ========== 文本/数值提取工具（与 RequirementConstraintExtractor 保持一致） ==========

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
        // FUN-04（2026-08-27）：去掉泛化的"内容"——题干内容/页面内容等与"通知内容"无关，
        // 会导致 CRUD 类一致对（addQuestion 等）被误判为"缺少 content 参数校验"
        mappings.put("content", new String[]{"通知内容", "消息内容", "content"});
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
                // null / empty 校验
                p + "\\s*==\\s*null",
                p + "\\s*!=\\s*null",
                "null\\s*==\\s*" + p,
                "null\\s*!=\\s*" + p,
                "\\b" + p + "\\.isempty\\(\\)",
                "\\b" + p + "\\.isblank\\(\\)",
                "\\b" + p + "\\.length\\(\\)\\s*[<>]=?\\s*0",
                "strings\\.isempty\\(\\s*" + p + "\\s*\\)",
                // 数值范围校验（针对 int/double 等原生类型）
                "\\b" + p + "\\s*[<>]=?\\s*-?\\d+",
                "\\b" + p + "\\s*%\\s*\\d+",
                "\\.get" + capitalize(p) + "\\s*\\(\\)\\s*[<>]=?\\s*-?\\d+",
        };
        for (String pt : patterns) {
            if (Pattern.compile(pt, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> extractStateValues(String req) {
        List<String> states = new ArrayList<>();
        if (req == null) return states;
        String[] candidates = {"PENDING", "PAID", "SHIPPED", "UNPUBLISHED", "PUBLISHED",
                "ACTIVE", "INACTIVE", "SUCCESS", "FAILED", "COMPLETED", "CANCELLED"};
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
        cnStates.put("已发货", "SHIPPED");
        cnStates.put("已完成", "COMPLETED");
        cnStates.put("已取消", "CANCELLED");
        cnStates.put("待处理", "PENDING");
        for (Map.Entry<String, String> e : cnStates.entrySet()) {
            if (req.contains(e.getKey()) && !states.contains(e.getValue())) {
                states.add(e.getValue());
            }
        }
        return states;
    }

    // ========== FUN-04b 类级量化边界核对（2026-08-27） ==========

    /**
     * 量化边界错配风险：需求给出显式数量边界（N 秒/N 分钟/最多 N 条/不超过 N 字符/重试 N 次…），
     * 本方法以不等式比较引用了某个数量，而该数值与需求不符（同一数量级内）。两类形态：
     * ① 比较对象是类静态常量（常量名出现在本方法体，值由 classEvidence 解析）；
     * ② 比较对象是方法内字面量（如 content.length() > 1000 而需求要求 500）——此时要求需求数值 N
     *    在本方法中完全未出现（防止"实际校验的就是需求值"被误伤）。
     * 通过「比较符号出现」双条件把缺陷归属到执行检查的方法，避免误伤清理/查询类辅助方法。
     *
     * @param classEvidence 类级证据行（ClassEvidenceScanner 扫描结果），null 时仅检查字面量形态
     */
    private static double quantitativeBoundMismatchRisk(String req, String code, List<String> classEvidence) {
        if (req == null || code == null) {
            return 0.0;
        }
        double risk = 0.0;
        boolean fired = false;
        // 形态①：命名常量
        Map<String, Integer> consts = extractStaticFinalConstants(classEvidence);
        for (Map.Entry<String, Integer> e : consts.entrySet()) {
            String name = e.getKey();
            int declared = e.getValue();
            Pattern cmp = Pattern.compile("(^|[^\\w'])" + Pattern.quote(name) + "\\s*[<>]=?"
                    + "|[<>]=?\\s*" + Pattern.quote(name) + "\\b");
            if (!cmp.matcher(code).find()) {
                continue;
            }
            for (int bound : extractQuantifiedBounds(req)) {
                if (bound != declared && bound > 0 && sameMagnitude(bound, declared)) {
                    risk += 0.45;
                    fired = true;
                    break;
                }
            }
        }
        // 形态②：字面量上限/下限比较（需求有量化边界、方法内有对该数量的不等式比较且数值不同、需求值未出现）
        if (!fired) {
            Matcher literal = Pattern.compile("[<>]=?\\s*(\\d{1,6})\\b").matcher(code);
            while (literal.find()) {
                int used = Integer.parseInt(literal.group(1));
                if (used <= 0) continue;
                for (int bound : extractQuantifiedBounds(req)) {
                    if (bound != used && bound > 0 && sameMagnitude(bound, used)
                            && !codeContainsWholeNumber(code, bound)) {
                        risk += 0.45;
                        fired = true;
                        return Math.min(risk, 0.45);
                    }
                }
            }
        }
        return fired ? Math.min(risk, 0.45) : 0.0;
    }

    /** 整数是否以完整 token 出现（前后均非数字），用于判断"需求数值未在本方法出现" */
    private static boolean codeContainsWholeNumber(String code, int n) {
        Matcher m = Pattern.compile("(?<!\\d)" + n + "(?!\\d)").matcher(code);
        return m.find();
    }

    /** 量化边界信号单独输出入口（FUN-04b 双判定管线的规则否决权使用） */
    public static double quantitativeBoundRiskSignal(String requirementText, String codeContent,
                                                     List<String> classEvidence) {
        return quantitativeBoundMismatchRisk(requirementText, codeContent, classEvidence);
    }

    /** 同数量级：比值落在 [1/5, 5]，排除日期/年份等弱相关的数值撞车 */
    private static boolean sameMagnitude(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return hi <= 5 * lo;
    }

    /**
     * 提取需求中的显式量化边界数值：形如「60秒」「15分钟」「每分钟最多10条」「不超过500字符」「重试3次」。
     * 只认带单位后缀的整数，避免把需求编号、年份、示例 ID 当成边界。
     */
    private static List<Integer> extractQuantifiedBounds(String req) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("(\\d{1,6})\\s*(秒|分钟|min|second|条|次|字符|个字|分)|"
                + "(?:最多|不低于|至少|不超过|上限|窗口)[^\\d]{0,6}(\\d{1,6})", Pattern.CASE_INSENSITIVE).matcher(req);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(3);
            if (g != null) {
                try {
                    out.add(Integer.parseInt(g));
                } catch (NumberFormatException ignored) {
                    // 超长数字忽略
                }
            }
        }
        return out;
    }

    /** 从类证据行解析 static final 数值常量：名称 -> 值 */
    private static Map<String, Integer> extractStaticFinalConstants(List<String> classEvidence) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (classEvidence == null) {
            return out;
        }
        for (String line : classEvidence) {
            if (line == null) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("static") || !lower.contains("final")) continue;
            Matcher nm = Pattern.compile("(\\w+)\\s*=\\s*(-?\\d+)").matcher(line);
            if (nm.find()) {
                try {
                    out.put(nm.group(1), Integer.parseInt(nm.group(2)));
                } catch (NumberFormatException ignored) {
                    // 溢出忽略
                }
            }
        }
        return out;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
