package com.traceguard.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.EmbeddingConfig;
import com.traceguard.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 客户端（GAP-004，provider=openai，默认）
 * 调用 <base-url>/embeddings（OpenAI 兼容协议，支持 DeepSeek/智谱等平台 embedding 端点），
 * 请求携带 model 与 input 数组；按 batch-size 分批；单批超时 timeout-seconds；失败重试 1 次。
 * 返回向量维度以端点为准：首个成功批次的维度记为基准，后续批次维度不一致视为失败（该批元素置 null）。
 * <p>注意：本类不再使用 @Service 自动装配，统一由 EmbeddingConfig 按 provider 选择并创建单例 Bean。</p>
 */
public class OpenAiEmbeddingClient implements EmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmbeddingConfig config;
    private final RestTemplate restTemplate;

    /** 全任务向量维度基准（-1 表示尚未建立；首个成功批次写入，后续批次须一致） */
    private volatile int baselineDim = -1;

    public OpenAiEmbeddingClient(LlmProperties properties) {
        this.config = properties.getEmbedding();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (config.getTimeoutSeconds() > 0 ? config.getTimeoutSeconds() : 30) * 1000;
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public boolean available() {
        return config != null
                && config.getBaseUrl() != null && !config.getBaseUrl().trim().isEmpty()
                && config.getApiKey() != null && !config.getApiKey().trim().isEmpty()
                && config.getModel() != null && !config.getModel().trim().isEmpty();
    }

    @Override
    public float[] embed(String text) {
        if (!available() || text == null || text.trim().isEmpty()) {
            return null;
        }
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> output = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return output;
        }
        int batchSize = config.getBatchSize() > 0 ? config.getBatchSize() : 16;
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            List<String> batch = texts.subList(start, end);
            List<float[]> batchVectors = callEmbeddings(batch);
            output.addAll(batchVectors);
        }
        return output;
    }

    /** 调用一次 /embeddings，失败重试 1 次；整批失败返回全 null 列表 */
    private List<float[]> callEmbeddings(List<String> batch) {
        List<float[]> result = new ArrayList<>(java.util.Collections.nCopies(batch.size(), null));
        try {
            Map<String, Object> resp = doPost(batch);
            parseEmbeddings(resp, batch.size(), result);
        } catch (Exception e) {
            LOGGER.debug("Embedding 首批调用失败，重试 1 次: {}", e.getMessage());
            try {
                Map<String, Object> resp = doPost(batch);
                parseEmbeddings(resp, batch.size(), result);
            } catch (Exception e2) {
                LOGGER.warn("Embedding 批量调用失败（重试后仍失败）: {}", e2.getMessage());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doPost(List<String> batch) {
        String url = config.getBaseUrl().replaceAll("/+$", "") + "/embeddings";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("input", batch);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            headers.setBearerAuth(config.getApiKey());
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, entity, Map.class);
    }

    /** 解析 OpenAI 兼容 /embeddings 响应：data[].embedding 与 data[].index */
    private void parseEmbeddings(Map<String, Object> resp, int size, List<float[]> output) {
        if (resp == null || !resp.containsKey("data")) {
            return;
        }
        Object dataObj = resp.get("data");
        if (!(dataObj instanceof List)) {
            return;
        }
        List<?> data = (List<?>) dataObj;
        for (Object item : data) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            int index = entry.get("index") instanceof Number ? ((Number) entry.get("index")).intValue() : -1;
            if (index < 0 || index >= size) {
                continue;
            }
            float[] vector = toFloatArray(entry.get("embedding"));
            if (vector == null) {
                continue;
            }
            // 维度基准校验：首个成功批次记录维度，后续批次维度不一致视为失败
            synchronized (this) {
                if (baselineDim < 0) {
                    baselineDim = vector.length;
                } else if (baselineDim != vector.length) {
                    LOGGER.warn("Embedding 维度不一致（期望 {}，实际 {}），该条视为失败", baselineDim, vector.length);
                    continue;
                }
            }
            output.set(index, vector);
        }
    }

    private float[] toFloatArray(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            JsonNode node;
            if (obj instanceof JsonNode) {
                node = (JsonNode) obj;
            } else {
                node = MAPPER.valueToTree(obj);
            }
            if (!node.isArray()) {
                return null;
            }
            float[] vector = new float[node.size()];
            for (int i = 0; i < node.size(); i++) {
                vector[i] = (float) node.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }
}