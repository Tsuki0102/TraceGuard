package com.traceguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceguard.llm.LangChainAdapter;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.LlmChain;
import com.traceguard.llm.LlmMessage;
import com.traceguard.llm.LlmResponse;
import com.traceguard.llm.Stage;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * AUD-02：LLM 语义判定（需求-代码一致性二审）。
 *
 * 在规则三维相似度（GAP-005）判定之后，若启用了大模型，对每个需求-代码对
 * 交由 LLM 结合需求原文与代码实现做语义级判定（是否一致 + GAP-020 四类缺陷主类型），
 * 用于纠正规则判定对"同主题但有行为缺陷"实现的漏检。
 *
 * 组件可独立于 Spring 使用（评测类直接构造 LlmCallExecutor 复用本类的静态方法）。
 */
@Slf4j
public class ConsistencyJudge {

    /** LLM 增强引擎：self=OpenAI 兼容客户端（default）；langchain=langchain4j 编排（GAP-044） */
    public enum Engine { SELF, LANGCHAIN }

    /** LLM 判定结果 */
    public static class Judgement {
        private final boolean consistent;
        private final String defectType;   // GAP-020 四类主类型（consistent=true 时为空）
        private final String reason;
        private final Engine engine;       // 实际生效的引擎（langchain 回退后记为 SELF）

        public Judgement(boolean consistent, String defectType, String reason, Engine engine) {
            this.consistent = consistent;
            this.defectType = defectType;
            this.reason = reason;
            this.engine = engine;
        }

        public boolean isConsistent() { return consistent; }
        public String getDefectType() { return defectType; }
        public String getReason() { return reason; }
        public Engine getEngine() { return engine; }
    }

    private final LlmCallExecutor executor;
    private final Engine engine;
    private final LangChainAdapter langChain;

    /** self 引擎构造器（默认） */
    public ConsistencyJudge(LlmCallExecutor executor) {
        this(executor, Engine.SELF, null);
    }

    /** 双引擎构造器：langchain 引擎需提供 adapter；adapter 不可用时自动降级 SELF */
    public ConsistencyJudge(LlmCallExecutor executor, Engine engine, LangChainAdapter langChain) {
        this.executor = executor;
        boolean lcUsable = engine == Engine.LANGCHAIN && langChain != null && langChain.isAvailable();
        this.engine = lcUsable ? Engine.LANGCHAIN : Engine.SELF;
        this.langChain = lcUsable ? langChain : null;
        if (engine == Engine.LANGCHAIN && !lcUsable) {
            log.warn("GAP-044：langchain 引擎不可用，已自动回退 self 引擎。");
        }
    }

    /**
     * 对单个需求-代码对执行 LLM 一致性判定。
     * 失败（LLM 未启用/调用失败/解析失败）返回 null，由调用方保留规则判定。
     * langchain 引擎调用异常时自动回退 self 引擎。
     */
    public Judgement judge(String requirementText, String codeSnippet,
                           double semanticSimilarity, double constraintMatch,
                           double invariantSatisfaction, double totalSimilarity,
                           String ruleDefectType) {
        if (executor == null) {
            return null;
        }
        String system = buildSystemPrompt();
        String user = buildUserPrompt(requirementText, codeSnippet, semanticSimilarity,
                constraintMatch, invariantSatisfaction, totalSimilarity);
        if (this.engine == Engine.LANGCHAIN && langChain != null) {
            try {
                String raw = langChain.generate(system, user);
                Judgement j = parse(raw);
                if (j != null) {
                    return new Judgement(j.isConsistent(), j.getDefectType(), j.getReason(), Engine.LANGCHAIN);
                }
                log.warn("GAP-044：langchain 返回解析失败，回退 self 引擎。");
            } catch (Exception e) {
                log.warn("GAP-044：langchain 引擎调用失败，回退 self 引擎：{}", e.getMessage());
            }
            // 回退 self
        }
        LlmResponse resp = executor.execute(Stage.CONSISTENCY_CHECK,
                List.of(LlmMessage.system(system), LlmMessage.user(user)));
        if (!resp.isSuccess() || resp.getContent() == null) {
            log.warn("GAP-049 judge LLM 调用失败/空响应：{}", resp.getContent());
            return null;
        }
        Judgement parsed = parse(resp.getContent());
        if (parsed == null && contentEmpty(resp.getContent())) {
            // 本地模型(Qwen/CodeLlama)偶发空 completion：重试一次以应对空响应，提升可用性
            log.warn("GAP-049 judge 空响应重试一次");
            LlmResponse retry = executor.execute(Stage.CONSISTENCY_CHECK,
                    List.of(LlmMessage.system(system), LlmMessage.user(user)));
            if (retry.isSuccess() && retry.getContent() != null) {
                parsed = parse(retry.getContent());
            }
        }
        return parsed == null ? null
                : new Judgement(parsed.isConsistent(), parsed.getDefectType(), parsed.getReason(), Engine.SELF);
    }

    /** 判断 LLM 响应是否为空/空白（本地模型偶发空 completion 场景） */
    private static boolean contentEmpty(String c) {
        return c == null || c.trim().isEmpty();
    }

    /** 构造判定 prompt（静态，供评测复用；prompt 精简以保证单次生成快速） */
    public static List<LlmMessage> buildMessages(String requirementText, String codeSnippet,
                                                 double semanticSimilarity, double constraintMatch,
                                                 double invariantSatisfaction, double totalSimilarity,
                                                 String ruleDefectType) {
        return List.of(
                LlmMessage.system(buildSystemPrompt()),
                LlmMessage.user(buildUserPrompt(requirementText, codeSnippet, semanticSimilarity,
                        constraintMatch, invariantSatisfaction, totalSimilarity)));
    }

    private static String buildSystemPrompt() {
        // 注意：不向 LLM 传递规则判定结论（ruleDefectType），避免在默认阈值偏低时把
        // 一致性对误导向"缺陷"；仅提供分项得分作为中性上下文，要求 LLM 独立复核。
        // 2026-08-23 强化"方法职责边界"原则：需求条目可能覆盖整个系统的多个功能点，
        // 而当前代码仅是其中一个方法——仅当当前方法自身承担的职责未实现/不符时才判缺陷，
        // 避免把需求中其他方法/其他环节的功能缺失误判给当前方法（qwen-max 10/55 误报根因）。
        return "你是需求-代码一致性审查专家，判定维度是需求-代码一致性而非代码质量。\n"
                + "【输入范围】你拿到的是【一条需求条目】与【一个代码方法】的对，不是全系统对照。"
                + "需求条目可能描述整个系统的多个功能点，但当前代码只是其中一个方法。\n"
                + "【判定原则】默认一致(consistent=true)。仅当【当前代码方法自身承担的职责】未实现，"
                + "或明确违反需求中【由该方法负责】的约束/状态流转/业务规则（含需求数值与代码常量不一致、"
                + "比较方向反转）时判不一致。\n"
                + "【职责边界——重要】需求中属于其他方法/其他环节的功能（如'创建订单后自动发送通知'中的"
                + "发送通知、'下单后扣减库存'中的扣库存），只要不是当前方法承担的部分，即使当前需求条目"
                + "提到了它、代码其他位置也没有，也【不判给当前方法】；当前方法已实现其自身职责即为一致。\n"
                + "【其他约束】NPE、资源泄漏等健壮性问题除非需求要求防御，否则不算缺陷；"
                + "需求未显式要求的具体校验（如分值/长度范围、额外的状态流转步骤）不判为缺陷。\n"
                + "【类型】判不一致时按缺失点选四类主类型之一："
                + "①约束条件不满足=功能已实现但缺少/未完整实现需求要求【该方法承担】的参数·边界·取值校验"
                + "（如金额>0、长度≤N、批量≤N、必填非空等校验缺失或校验值不符）；"
                + "②业务逻辑不一致=功能已实现但业务规则·阈值·状态流转·数值·比较方向与需求不符"
                + "（如比较/判断方向反转、窗口或阈值与需求不一致、状态未按需求流转、规则不完整）；"
                + "③需求缺失=需求声明的功能或方法整体未实现/未覆盖；"
                + "④代码超范围实现=实现需求之外的功能。\n"
                + "【输出】严格只输出JSON（不要markdown围栏）：{\"consistent\":true或false,\"defectType\":"
                + "\"业务逻辑不一致|约束条件不满足|需求缺失|代码超范围实现\",\"reason\":\"≤20字\"}，"
                + "defectType仅在不一致时填四类之一。";
    }

    private static String buildUserPrompt(String requirementText, String codeSnippet,
                                           double semanticSimilarity, double constraintMatch,
                                           double invariantSatisfaction, double totalSimilarity) {
        StringBuilder user = new StringBuilder();
        user.append("需求：").append(requirementText == null ? "" : requirementText).append("\n\n");
        user.append("代码：\n").append(truncate(codeSnippet, 2500)).append("\n\n");
        user.append("得分参考：语义=").append(String.format("%.2f", semanticSimilarity))
                .append(" 约束=").append(String.format("%.2f", constraintMatch))
                .append(" 不变量=").append(String.format("%.2f", invariantSatisfaction))
                .append(" 综合=").append(String.format("%.2f", totalSimilarity));
        return user.toString();
    }

    /** 解析 LLM 响应为 Judgement；非法响应返回 null（触发降级保留规则判定） */
    public static Judgement parse(String content) {
        JsonNode node = LlmChain.parseJson(content);
        if (node == null) {
            log.warn("judge 响应解析失败（非 JSON），原始响应前 300 字符：{}",
                    content == null ? "null" : content.substring(0, Math.min(300, content.length())));
            return null;
        }
        boolean consistent = node.path("consistent").asBoolean(true);
        String type = normalizeType(node.path("defectType").asText(""));
        String reason = node.path("reason").asText("");
        if (!consistent && type.isEmpty()) {
            // LLM 判不一致但未给出类型 -> 视为解析失败，保留规则判定
            log.warn("judge 判不一致但缺陷类型为空，视为解析失败，原始响应前 200 字符：{}",
                    content == null ? "null" : content.substring(0, Math.min(200, content.length())));
            return null;
        }
        return new Judgement(consistent, type, reason, Engine.SELF);
    }

    /** 将 LLM 输出的缺陷类型规范化到 GAP-020 四类主类型 */
    static String normalizeType(String t) {
        if (t == null) {
            return "";
        }
        if (t.contains("需求缺失")) {
            return "需求缺失";
        }
        if (t.contains("代码超范围") || t.contains("超范围实现") || t.contains("超范围")) {
            return "代码超范围实现";
        }
        if (t.contains("约束") || t.contains("不变量") || t.contains("校验")
                || t.contains("空") || t.contains("异常") || t.contains("参数")) {
            return "约束条件不满足";
        }
        if (t.contains("业务逻辑") || t.contains("逻辑") || t.contains("行为") || t.contains("实现")) {
            return "业务逻辑不一致";
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
