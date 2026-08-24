package com.traceguard.llm;

/**
 * LLM 客户端抽象（GAP-021）
 * 统一 chat 入口，OpenAI 兼容 /chat/completions，多 provider（DeepSeek/GLM/local 预留）。
 */
public interface LlmClient {

    /** 同步 chat 调用（OpenAI 兼容 /chat/completions）；失败返回 success=false，不抛异常 */
    LlmResponse chat(LlmRequest request);

    /** provider 名称（配置键：deepseek/glm/local） */
    String providerName();
}
