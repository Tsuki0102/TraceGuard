package com.traceguard.llm;

/**
 * LLM 响应 DTO（失败时 success=false，不抛异常）
 */
public class LlmResponse {

    private boolean success;
    /** choices[0].message.content */
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;
    private String errorMessage;

    public static LlmResponse ok(String content, Integer promptTokens, Integer completionTokens) {
        LlmResponse r = new LlmResponse();
        r.success = true;
        r.content = content;
        r.promptTokens = promptTokens;
        r.completionTokens = completionTokens;
        return r;
    }

    public static LlmResponse fail(String errorMessage) {
        LlmResponse r = new LlmResponse();
        r.success = false;
        r.errorMessage = errorMessage;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
