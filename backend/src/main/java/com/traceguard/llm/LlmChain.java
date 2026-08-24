package com.traceguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain 风格链式 prompt 调用（GAP-021 步骤 6）
 * 需求 -> Kripke 结构 JSON -> Alloy 规约代码，每步含 JSON 解析与错误反馈重试。
 */
public class LlmChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmChain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ALLOY_FENCE = Pattern.compile("```(?:alloy)?\\s*\\n?([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    /** 链式执行结果 */
    public static class ChainResult {
        private final boolean success;
        /** Kripke 结构（states/transitions/constraints/invariants） */
        private JsonNode kripke;
        /** Alloy 规约代码（已剥离 markdown 围栏） */
        private String alloyCode;
        private final String errorMessage;

        private ChainResult(boolean success, JsonNode kripke, String alloyCode, String errorMessage) {
            this.success = success;
            this.kripke = kripke;
            this.alloyCode = alloyCode;
            this.errorMessage = errorMessage;
        }

        public static ChainResult ok(JsonNode kripke, String alloyCode) {
            return new ChainResult(true, kripke, alloyCode, null);
        }

        public static ChainResult fail(String errorMessage) {
            return new ChainResult(false, null, null, errorMessage);
        }

        public boolean isSuccess() { return success; }
        public JsonNode getKripke() { return kripke; }
        public String getAlloyCode() { return alloyCode; }
        public String getErrorMessage() { return errorMessage; }
    }

    private final LlmCallExecutor executor;

    public LlmChain(LlmCallExecutor executor) {
        this.executor = executor;
    }

    /**
     * 链式执行：需求文本 -> Kripke 结构 JSON -> Alloy 规约代码
     * 每步解析失败 -> 用错误反馈重试 1 次 -> 仍失败返回失败标记（上层降级规则实现）。
     */
    public ChainResult requirementToAlloy(String requirementText) {
        // 步骤 1：需求 -> Kripke 结构 JSON（Stage.REQUIREMENT，GLM 路由）
        JsonNode kripke = step1Kripke(requirementText);
        if (kripke == null) {
            return ChainResult.fail("需求 -> Kripke 结构解析失败");
        }
        // 步骤 2：需求原文 + Kripke JSON -> Alloy 代码（Stage.ALLOY，DeepSeek 路由）
        String alloy = step2Alloy(requirementText, kripke);
        if (alloy == null) {
            return ChainResult.fail("Kripke -> Alloy 规约生成失败");
        }
        return ChainResult.ok(kripke, alloy);
    }

    /** 步骤 1：需求文本 -> Kripke 结构 JSON（states/transitions/constraints/invariants） */
    private JsonNode step1Kripke(String requirementText) {
        String system = "你是软件需求分析专家。请将需求文本建模为 Kripke 结构，严格输出 JSON（不要 markdown 围栏），"
                + "格式：{\"states\":[{\"name\":\"...\",\"description\":\"...\"}],"
                + "\"transitions\":[{\"from\":\"...\",\"to\":\"...\",\"condition\":\"...\"}],"
                + "\"constraints\":[\"...\"],\"invariants\":[\"...\"]}";
        List<LlmMessage> messages = List.of(
                LlmMessage.system(system),
                LlmMessage.user(requirementText));
        LlmResponse resp = executor.execute(Stage.REQUIREMENT, messages);
        if (!resp.isSuccess() || resp.getContent() == null) {
            return null;
        }
        JsonNode node = parseJson(resp.getContent());
        if (node != null) {
            return node;
        }
        // 解析失败 -> 错误反馈重试 1 次
        String feedback = "你的上一步输出不是合法 JSON：" + truncate(resp.getContent(), 300)
                + "。请重新输出严格 JSON，不要包含任何解释性文字或 markdown 围栏。";
        LlmResponse retry = executor.execute(Stage.REQUIREMENT,
                List.of(LlmMessage.system(system), LlmMessage.user(requirementText), LlmMessage.assistant(resp.getContent()), LlmMessage.user(feedback)));
        return retry.isSuccess() ? parseJson(retry.getContent()) : null;
    }

    /** 步骤 2：需求原文 + Kripke JSON -> Alloy 规约代码（输出约定在 ```alloy 代码块中） */
    private String step2Alloy(String requirementText, JsonNode kripke) {
        String system = "你是形式化验证专家。请基于需求与 Kripke 结构生成 Alloy 6 规约："
                + "1) 用 sig 声明实体与状态域；2) 用 pred/fact 表达状态转移与不变量；3) 至少一条 check 断言；"
                + "4) 输出放在 ```alloy 代码块内，只输出代码，不要解释。";
        String user = "需求文本：\n" + requirementText + "\n\nKripke 结构 JSON：\n"
                + (kripke != null ? kripke.toString() : "{}");
        List<LlmMessage> messages = List.of(LlmMessage.system(system), LlmMessage.user(user));
        LlmResponse resp = executor.execute(Stage.ALLOY, messages);
        if (!resp.isSuccess() || resp.getContent() == null) {
            return null;
        }
        String alloy = stripFences(resp.getContent());
        if (isBlankAlloy(alloy)) {
            // 输出为空/无可提取代码 -> 错误反馈重试 1 次
            String feedback = "你的上一步输出未包含合法的 Alloy 代码块（应使用 ```alloy 围栏）。请只输出代码。";
            LlmResponse retry = executor.execute(Stage.ALLOY,
                    List.of(LlmMessage.system(system), LlmMessage.user(user), LlmMessage.assistant(resp.getContent()), LlmMessage.user(feedback)));
            if (retry.isSuccess()) {
                alloy = stripFences(retry.getContent());
            } else {
                alloy = null;
            }
        }
        return isBlankAlloy(alloy) ? null : alloy;
    }

    /** 剥离后是否为空白（视为未产出有效 Alloy 代码） */
    private static boolean isBlankAlloy(String alloy) {
        return alloy == null || alloy.trim().isEmpty();
    }

    /** 剥离 markdown 代码围栏（```alloy ... ``` / ``` ... ```），无围栏时原样返回 */
    public static String stripFences(String content) {
        if (content == null) {
            return null;
        }
        Matcher m = ALLOY_FENCE.matcher(content);
        if (m.find()) {
            return m.group(1).trim();
        }
        return content.trim();
    }

    /** 宽松 JSON 解析：尝试整体解析；失败则尝试截取首个 {...} 或 [...] 块 */
    public static JsonNode parseJson(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            // 尝试从响应中截取首个 JSON 对象/数组
            int start = -1;
            char open = 0;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '{' || c == '[') {
                    start = i;
                    open = c;
                    break;
                }
            }
            if (start < 0) {
                return null;
            }
            char close = open == '{' ? '}' : ']';
            int depth = 0;
            for (int i = start; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        try {
                            return MAPPER.readTree(trimmed.substring(start, i + 1));
                        } catch (Exception e2) {
                            return null;
                        }
                    }
                }
            }
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
