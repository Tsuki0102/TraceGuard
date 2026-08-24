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
     * 检测需求-代码对的缺陷风险分。
     */
    public static double detectDefectRisk(String requirementText, String codeContent) {
        if (requirementText == null || codeContent == null) {
            return 0.0;
        }
        String req = requirementText;
        String code = codeContent;
        double risk = 0.0;

        risk += stateMismatchRisk(req, code);
        risk += numericMismatchRisk(req, code);
        risk += paramValidationMissingRisk(req, code);
        risk += logicInversionRisk(req, code);
        risk += commonCodeBugRisk(code);
        risk += impliedBusinessRuleMissingRisk(req, code);

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

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
