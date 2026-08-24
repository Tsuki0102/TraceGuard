package com.traceguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.LlmProperties.ProviderConfig;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 LLM 客户端（GAP-021）
 * 基于 RestTemplate 调用 <provider.base-url>/chat/completions，携带 Authorization: Bearer <api-key>。
 * jsonMode=true 时请求体加 response_format=json_object；若目标端点不支持（HTTP 4xx），
 * 自动降级为在 system prompt 追加"仅输出合法 JSON"后重发一次。
 *
 * AUD-02：改用 Apache HttpClient 实现。原 SimpleClientHttpRequestFactory（HttpURLConnection）
 * 对 DashScope 等流式/慢响应读取超时失效，导致调用无限挂起（熔断/重试形同虚设）；
 * Apache HttpClient 的 socket 超时可靠触发，超时调用进入 LlmCallExecutor 的重试与熔断降级路径。
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String providerName;
    private final ProviderConfig config;
    private final RestTemplate restTemplate;

    public OpenAiLlmClient(String providerName, ProviderConfig config) {
        this.providerName = providerName;
        this.config = config;
        int timeoutMs = (config.getTimeoutSeconds() > 0 ? config.getTimeoutSeconds() : 60) * 1000;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnTotal(8)
                .setMaxConnPerRoute(8)
                .disableAutomaticRetries()
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> body = buildBody(request);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
                // trim 防止控制台复制的 key 带首尾空白/换行导致 401
                headers.setBearerAuth(config.getApiKey().trim());
            }
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(url, entity, Map.class);
            return parseResponse(resp);
        } catch (Exception e) {
            // jsonMode 且端点不支持 response_format（HTTP 4xx）：降级为在 system prompt 追加 JSON 约束后重发
            if (request.isJsonMode() && isClientError(e)) {
                return retryWithoutJsonMode(url, request);
            }
            LOGGER.debug("LLM[{}] 调用失败: {}", providerName, e.getMessage());
            return LlmResponse.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** 组装请求体（jsonMode 时加 response_format） */
    private Map<String, Object> buildBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmMessage m : request.getMessages()) {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());
            messages.add(msg);
        }
        body.put("messages", messages);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.isJsonMode()) {
            Map<String, String> fmt = new LinkedHashMap<>();
            fmt.put("type", "json_object");
            body.put("response_format", fmt);
        }
        return body;
    }

    /** 解析 OpenAI 兼容响应：choices[0].message.content + usage */
    @SuppressWarnings("unchecked")
    private LlmResponse parseResponse(Map<String, Object> resp) {
        if (resp == null || !resp.containsKey("choices")) {
            return LlmResponse.fail("响应缺少 choices 字段");
        }
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                return LlmResponse.fail("响应 choices 为空");
            }
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            if (msg == null || msg.get("content") == null) {
                return LlmResponse.fail("响应 message.content 为空");
            }
            Integer promptTokens = null;
            Integer completionTokens = null;
            Object usage = resp.get("usage");
            if (usage instanceof Map) {
                Map<String, Object> u = (Map<String, Object>) usage;
                promptTokens = u.get("prompt_tokens") instanceof Number
                        ? ((Number) u.get("prompt_tokens")).intValue() : null;
                completionTokens = u.get("completion_tokens") instanceof Number
                        ? ((Number) u.get("completion_tokens")).intValue() : null;
            }
            return LlmResponse.ok((String) msg.get("content"), promptTokens, completionTokens);
        } catch (Exception e) {
            return LlmResponse.fail("响应解析失败: " + e.getMessage());
        }
    }

    /** 判断异常是否为 4xx（response_format 不支持） */
    private boolean isClientError(Exception e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof org.springframework.web.client.HttpClientErrorException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** jsonMode 降级：system prompt 追加"仅输出合法 JSON"，去掉 response_format 重发一次 */
    private LlmResponse retryWithoutJsonMode(String url, LlmRequest request) {
        try {
            LlmRequest req2 = new LlmRequest();
            req2.setModel(request.getModel());
            req2.setTemperature(request.getTemperature());
            req2.setMaxTokens(request.getMaxTokens());
            req2.setJsonMode(false);
            List<LlmMessage> messages = new ArrayList<>(request.getMessages());
            String jsonHint = "【重要】请仅输出合法 JSON，不要输出任何其他内容（不要使用 markdown 代码围栏）。";
            if (messages.isEmpty() || !"system".equals(messages.get(0).getRole())) {
                messages.add(0, LlmMessage.system(jsonHint));
            } else {
                LlmMessage sys = messages.get(0);
                messages.set(0, LlmMessage.system((sys.getContent() == null ? "" : sys.getContent()) + "\n" + jsonHint));
            }
            req2.setMessages(messages);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
                headers.setBearerAuth(config.getApiKey().trim());
            }
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(buildBody(req2), headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(url, entity, Map.class);
            return parseResponse(resp);
        } catch (Exception e2) {
            LOGGER.debug("LLM[{}] json 降级重试失败: {}", providerName, e2.getMessage());
            return LlmResponse.fail(e2.getMessage() != null ? e2.getMessage() : e2.getClass().getSimpleName());
        }
    }
}
