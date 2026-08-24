package com.traceguard.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 请求 DTO（OpenAI 兼容 /chat/completions）
 */
public class LlmRequest {

    private String model;
    private List<LlmMessage> messages = new ArrayList<>();
    private Double temperature;
    private Integer maxTokens;
    /** 期望 JSON 输出（response_format=json_object） */
    private boolean jsonMode;
    /** 覆盖 provider 默认超时（秒） */
    private Integer timeoutSeconds;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<LlmMessage> getMessages() { return messages; }
    public void setMessages(List<LlmMessage> messages) { this.messages = messages; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public boolean isJsonMode() { return jsonMode; }
    public void setJsonMode(boolean jsonMode) { this.jsonMode = jsonMode; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
