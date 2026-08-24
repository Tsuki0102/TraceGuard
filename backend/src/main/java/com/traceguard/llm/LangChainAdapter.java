package com.traceguard.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * GAP-044：LangChain 真集成适配器。
 * 基于 langchain4j（dev.langchain4j）编排「需求-代码一致性判定」大模型调用链：
 * 以 OpenAiChatModel 为底层（GAP-043 指向本地 Ollama 的 CodeLlama 实例），
 * 通过 System/User 双角色消息构造判定链，返回模型原始文本。
 *
 * 设计要点：
 * 1. 真集成：直接依赖 langchain4j API，非 HTTP 透传包装；
 * 2. 可选：langchain4j 缺失或被禁用时 {@link #isAvailable()} 返回 false，调用方自动回退 self 引擎；
 * 3. 仅负责「模型调用编排」，判定 prompt 构造与结果解析复用 ConsistencyJudge 既有逻辑，保证判定口径一致。
 */
@Slf4j
public final class LangChainAdapter {

    private final OpenAiChatModel model;
    private final boolean available;

    private LangChainAdapter(OpenAiChatModel model) {
        this.model = model;
        this.available = model != null;
    }

    /**
     * 探测 langchain4j 是否可用并构造适配器。
     *
     * @param baseUrl   OpenAI 兼容端点（GAP-043：本地 Ollama 为 http://localhost:11434/v1）
     * @param modelName 模型名（如 codellama:7b / codellama:13b）
     * @param timeoutSec 单次调用超时（秒）
     * @return 适配器；langchain4j 缺失或构造失败时返回不可用的空适配器（available=false）
     */
    public static LangChainAdapter create(String baseUrl, String modelName, int timeoutSec) {
        try {
            // 探测 langchain4j 是否位于 classpath（optional 依赖场景）
            Class.forName("dev.langchain4j.model.openai.OpenAiChatModel");
            OpenAiChatModel m = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey("ollama") // Ollama 不校验密钥，占位即可
                    .modelName(modelName)
                    .timeout(java.time.Duration.ofSeconds(timeoutSec))
                    .temperature(0.0)
                    .build();
            log.info("GAP-044：LangChain 适配器已就绪（baseUrl={}, model={}）", baseUrl, modelName);
            return new LangChainAdapter(m);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.warn("GAP-044：langchain4j 不在 classpath，LangChain 引擎不可用，将回退 self 引擎。", e);
            return new LangChainAdapter(null);
        } catch (Exception e) {
            log.warn("GAP-044：LangChain 适配器构造失败，将回退 self 引擎。", e);
            return new LangChainAdapter(null);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 调用 LangChain 编排的模型链，返回原始文本。
     * 失败时抛异常，由调用方回退 self 引擎。
     */
    public String generate(String systemPrompt, String userPrompt) {
        if (!available) {
            throw new IllegalStateException("LangChain 适配器不可用");
        }
        Response<AiMessage> resp = model.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt));
        AiMessage msg = resp.content();
        if (msg == null) {
            throw new IllegalStateException("LangChain 模型返回为空");
        }
        return msg.text();
    }
}
