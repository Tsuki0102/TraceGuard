package com.traceguard.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.LlmProperties;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.LlmMessage;
import com.traceguard.llm.LlmResponse;
import com.traceguard.llm.Stage;
import com.traceguard.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体编排核心（一期：只读工具 + ReAct 循环）
 *
 * 协议（文本级 function calling，兼容所有 OpenAI 兼容 provider，含本地 CodeLlama）：
 *  - 模型要调用工具时，只输出一个 JSON 对象：{"tool":"工具名","args":{...}}
 *  - 已有足够信息作答时，直接输出自然语言最终回答（Markdown）
 * 循环：LLM 决策 -> 工具执行 -> 观察结果回喂 -> 直到最终回答或步数上限。
 *
 * 流式：内部 LLM 调用为整段返回（复用 LlmCallExecutor 的限流/熔断/落库治理），
 * SSE 侧以 status 事件（思考/工具进度）+ delta 分片（最终回答）模拟渐进输出。
 *
 * 数据权限：工作线程先恢复 UserContext 再执行循环，所有工具内部校验随之生效。
 */
@Service
public class AgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次提问的最大决策步数（LLM 调用次数上限，性能与费用保护） */
    private static final int MAX_STEPS = 6;
    /** 工具观察结果回喂时的最大长度（字符） */
    private static final int MAX_OBSERVATION_LEN = 3000;
    /** 会话保留消息条数（超出丢弃最早） */
    private static final int MAX_HISTORY_MESSAGES = 24;
    /** delta 分片大小与间隔（模拟流式，单位：字符/毫秒） */
    private static final int DELTA_CHUNK_SIZE = 120;
    private static final long DELTA_INTERVAL_MS = 40;
    /** 待确认动作有效期（毫秒） */
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;

    @Autowired
    private AgentTools agentTools;

    @Autowired
    private LlmCallExecutor executor;

    @Autowired
    private LlmProperties llmProperties;

    /** 会话存储：sessionId -> 会话（LLM 侧消息历史） */
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    /** 待用户确认的动作（human-in-the-loop）：token -> 动作 */
    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    /** 同一会话串行执行锁，防止并发请求交叉写入历史 */
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    /** Agent 执行线程池（daemon；SSE 超时兜底 180s） */
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-worker");
        t.setDaemon(true);
        return t;
    });

    // ==================== 对外 API ====================

    /**
     * 发起一轮智能体对话（异步执行，经 SSE 推送进度与结果）
     *
     * @param sessionId 前端会话ID（首次由前端生成 UUID）
     * @param message   用户消息
     * @param userId/username/role 请求线程捕获的用户上下文（工作线程恢复用）
     * @return SseEmitter（调用方直接返回给容器）
     */
    public SseEmitter chatStream(String sessionId, String message,
                                 Long userId, String username, String role) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean lock = running.computeIfAbsent(sessionId, k -> new AtomicBoolean());
        if (sessionId.isEmpty() || message == null || message.trim().isEmpty()) {
            sendEvent(emitter, "error", Map.of("message", "sessionId 与 message 不能为空"));
            emitter.complete();
            return emitter;
        }
        if (!lock.compareAndSet(false, true)) {
            sendEvent(emitter, "error", Map.of("message", "当前会话有请求正在处理，请稍候"));
            emitter.complete();
            return emitter;
        }
        worker.execute(() -> {
            try {
                UserContext.set(userId, username, role);
                EventSink sink = (event, data) -> sendEvent(emitter, event, data);
                runLoop(sink, sessionId, message, MAX_STEPS);
            } catch (Exception e) {
                LOGGER.warn("智能体执行异常: {}", e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("message", "智能体执行失败: " + e.getMessage()));
            } finally {
                UserContext.clear();
                lock.set(false);
            }
            emitter.complete();
        });
        return emitter;
    }

    /** 事件出口抽象：SSE 推送与无头收集（巡检）共用同一 ReAct 循环 */
    @FunctionalInterface
    public interface EventSink {
        void send(String event, Object data);
    }

    /**
     * 无头执行一轮 Agent 循环（定时巡检/手动巡检用）：收集最终回答文本。
     * 循环失败（LLM 调用失败/步数耗尽）时抛 IllegalStateException。
     */
    public String runHeadless(String sessionId, String message, int maxSteps) {
        StringBuilder out = new StringBuilder();
        String[] error = {null};
        EventSink sink = (event, data) -> {
            if ("delta".equals(event) && data instanceof Map) {
                Object text = ((Map<?, ?>) data).get("text");
                if (text != null) {
                    out.append(text);
                }
            } else if ("error".equals(event) && data instanceof Map) {
                error[0] = String.valueOf(((Map<?, ?>) data).get("message"));
            }
        };
        try {
            UserContext.set(0L, "system", "admin");
            runLoop(sink, sessionId, message, maxSteps);
        } finally {
            UserContext.clear();
        }
        if (error[0] != null) {
            throw new IllegalStateException(error[0]);
        }
        return out.toString();
    }

    /** 重置会话（清空历史） */
    public void resetSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 用户确认/取消执行待确认动作（同步执行；HTTP 线程内 UserContext 已由拦截器就绪）
     *
     * @return {success, message} 供前端直接展示
     */
    public Map<String, Object> confirmAction(String sessionId, String token, boolean approve) {
        PendingAction pa = token != null ? pendingActions.remove(token) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (pa == null || !pa.sessionId.equals(sessionId)
                || System.currentTimeMillis() - pa.createdAt > PENDING_TTL_MS) {
            out.put("success", false);
            out.put("message", "待确认动作不存在或已过期，请重新发起请求");
            return out;
        }
        String actionNote = "（用户" + (approve ? "确认" : "取消") + "执行 " + pa.toolName + "：" + pa.summary + "）";
        if (!approve) {
            out.put("success", true);
            out.put("message", "已取消：" + pa.summary);
        } else {
            try {
                AgentTool tool = agentTools.get(pa.toolName);
                Object result = tool != null ? tool.execute(pa.args) : null;
                String json = result == null ? "{}" : MAPPER.writeValueAsString(result);
                if (json.length() > 800) json = json.substring(0, 800) + "…（已截断）";
                out.put("success", true);
                out.put("message", "✅ 执行完成：" + pa.summary + "\n\n结果：" + json);
            } catch (Exception e) {
                out.put("success", false);
                out.put("message", "❌ 执行失败：" + pa.summary + "\n\n原因："
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        // 确认交互写入会话历史，后续多轮对话可感知
        AgentSession session = sessions.get(sessionId);
        if (session != null) {
            remember(session, "user", actionNote);
            remember(session, "assistant", out.get("message").toString());
        }
        return out;
    }

    // ==================== Agent 主循环 ====================

    private void runLoop(EventSink sink, String sessionId, String message, int maxSteps) {
        if (!llmProperties.isEnabled()) {
            sink.send("error", Map.of("message",
                    "大模型未启用，智能体不可用。请联系管理员在「系统配置-大模型配置」中启用。"));
            return;
        }
        AgentSession session = sessions.compute(sessionId, (k, v) -> {
            AgentSession s = v == null ? new AgentSession() : v;
            s.touch();
            return s;
        });

        // 组装本轮消息：系统提示词（每次重建，工具清单/日期保持最新）+ 历史 + 用户消息
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(maxSteps)));
        synchronized (session) {
            messages.addAll(session.getHistory());
        }
        messages.add(LlmMessage.user(message));

        for (int step = 1; step <= maxSteps; step++) {
            sink.send("status", Map.of("step", step, "phase", "thinking"));
            LlmResponse resp = executor.execute(Stage.AGENT_CHAT, messages);
            if (!resp.isSuccess() && resp.getErrorMessage() != null
                    && resp.getErrorMessage().contains("路由失败")) {
                // 兜底：tg_llm_config 存量配置无 agent 路由键时，回退 code-explain 环节路由
                resp = executor.execute(Stage.CODE_EXPLAIN, messages);
            }
            if (!resp.isSuccess() || resp.getContent() == null || resp.getContent().isBlank()) {
                sink.send("error", Map.of("message",
                        "大模型调用失败: " + (resp.getErrorMessage() == null ? "空响应" : resp.getErrorMessage())));
                return;
            }
            String content = resp.getContent().trim();

            // 解析决策：工具调用 or 最终回答
            JsonNode toolCall = parseToolCall(content);
            if (toolCall == null) {
                // 最终回答：写入会话历史 + 分片推送
                remember(session, "user", message);
                remember(session, "assistant", content);
                streamDelta(sink, content);
                return;
            }

            String toolName = toolCall.path("tool").asText("");
            Map<String, Object> args = MAPPER.convertValue(toolCall.path("args"), Map.class);
            sink.send("status", Map.of("step", step, "phase", "tool", "tool", toolName));

            String observation;
            AgentTool tool = agentTools.get(toolName);
            if (tool == null) {
                observation = "错误：未注册的工具 \"" + toolName + "\"，可用工具：" + agentTools.names();
            } else if (tool.requiresConfirm()) {
                // 动作类工具：不直接执行，生成待确认动作，向用户弹出确认卡片（human-in-the-loop）
                String summary = tool.confirmSummary(args);
                String token = java.util.UUID.randomUUID().toString().replace("-", "");
                pendingActions.put(token, new PendingAction(sessionId, toolName, args, summary));
                remember(session, "assistant", "（提议执行动作工具 " + toolName + "，参数 " + args + "，等待用户确认）");
                sink.send("confirm", Map.of("token", token, "tool", toolName, "summary", summary));
                return;
            } else {
                try {
                    Object result = tool.execute(args);
                    observation = MAPPER.writeValueAsString(result);
                    if (observation.length() > MAX_OBSERVATION_LEN) {
                        observation = observation.substring(0, MAX_OBSERVATION_LEN) + "…（已截断）";
                    }
                } catch (Exception e) {
                    observation = "工具执行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }

            // 决策与观察结果写入消息流（assistant 决策 + user 观察）
            messages.add(LlmMessage.assistant(content));
            messages.add(LlmMessage.user("工具[" + toolName + "]执行结果：\n" + observation));
            if (step == maxSteps) {
                sink.send("status", Map.of("step", step, "phase", "thinking",
                        "note", "已达最大步数，请基于已有信息作答"));
            }
        }

        // 步数耗尽兜底：模型始终未给出最终回答
        sink.send("error", Map.of("message", "已达最大推理步数仍未完成回答，请缩小问题范围后重试"));
    }

    // ==================== 决策解析 ====================

    /**
     * 解析模型输出中的工具调用 JSON：{"tool":"...","args":{...}}
     * 兼容：整体即 JSON / markdown 围栏内的 JSON / JSON 前后附带解释文字的场景。
     * 非工具调用（最终回答）返回 null。
     */
    private JsonNode parseToolCall(String content) {
        String candidate = stripFences(content).trim();
        // 快速判定：不含 "tool" 键名则必为最终回答
        int idx = candidate.indexOf("\"tool\"");
        if (idx < 0) {
            return null;
        }
        JsonNode node = tryParse(candidate);
        if (node == null) {
            // 从 "tool" 键位置向前找 '{'，向后找配对 '}' 截取候选 JSON
            int start = candidate.lastIndexOf('{', idx);
            if (start >= 0) {
                int depth = 0;
                for (int i = start; i < candidate.length(); i++) {
                    char c = candidate.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            node = tryParse(candidate.substring(start, i + 1));
                            break;
                        }
                    }
                }
            }
        }
        if (node != null && node.has("tool") && !node.path("tool").asText("").isEmpty()) {
            return node;
        }
        return null;
    }

    private JsonNode tryParse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFences(String s) {
        if (s == null) return null;
        return s.replaceAll("(?s)```(?:json)?\\s*(.*?)```", "$1").trim();
    }

    // ==================== 系统提示词 / 会话 / SSE ====================

    private String buildSystemPrompt(int maxSteps) {
        return "你是 TraceGuard（软件需求-代码一致性验证与缺陷自动检测系统）的智能体助手。\n"
                + "你可以调用系统提供的只读工具查询项目、需求、缺陷、一致性判定、趋势统计等真实数据，"
                + "基于查询结果用中文准确回答，而不是凭空编造数据。\n\n"
                + "## 可用工具\n" + agentTools.describeTools() + "\n"
                + "## 工作规则\n"
                + "1. 需要真实数据时调用工具；涉及项目数据的回答必须先查询，禁止编造数字。\n"
                + "2. 调用工具时只输出一个 JSON 对象（可放 markdown 围栏内），格式："
                + "{\"tool\":\"工具名\",\"args\":{参数}}，不要输出其他文字。\n"
                + "3. 已有足够信息或回答通用知识类问题时，直接输出最终回答（Markdown，简洁、分点），"
                + "不要再输出工具 JSON。\n"
                + "4. 最多进行 " + maxSteps + " 轮决策，尽量在 2~3 轮内完成。\n"
                + "5. 数据中的 id/level/status 等枚举值含义见工具描述；向用户展示时转成中文。\n"
                + "6. 用户无权访问的数据（工具报无权限/不存在）如实告知，不要猜测。\n"
                + "7. 当前日期：" + java.time.LocalDate.now() + "。\n"
                + "8. 描述中标注\"动作类\"的工具不会立即执行：你调用后系统会向用户弹出确认卡片，"
                + "用户确认后才会执行并在下一条消息中告知结果。你不要重复调用同一动作，也不要向用户索要确认。\n";
    }

    /** 会话历史写入（裁剪到上限，锁内操作） */
    private void remember(AgentSession session, String role, String content) {
        synchronized (session) {
            List<LlmMessage> history = session.getHistory();
            history.add(new LlmMessage(role, content));
            while (history.size() > MAX_HISTORY_MESSAGES) {
                history.remove(0);
            }
        }
    }

    /** 最终回答分片推送（模拟流式渐进渲染） */
    private void streamDelta(EventSink sink, String content) {
        try {
            for (int i = 0; i < content.length(); i += DELTA_CHUNK_SIZE) {
                String chunk = content.substring(i, Math.min(content.length(), i + DELTA_CHUNK_SIZE));
                sink.send("delta", Map.of("text", chunk));
                if (i + DELTA_CHUNK_SIZE < content.length()) {
                    Thread.sleep(DELTA_INTERVAL_MS);
                }
            }
            sink.send("done", Map.of("ok", true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.send("done", Map.of("ok", true));
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            LOGGER.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /** 待用户确认的动作（human-in-the-loop，TTL 内有效） */
    private static class PendingAction {
        final String sessionId;
        final String toolName;
        final Map<String, Object> args;
        final String summary;
        final long createdAt = System.currentTimeMillis();

        PendingAction(String sessionId, String toolName, Map<String, Object> args, String summary) {
            this.sessionId = sessionId;
            this.toolName = toolName;
            this.args = args;
            this.summary = summary;
        }
    }
}
