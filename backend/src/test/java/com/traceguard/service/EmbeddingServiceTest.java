package com.traceguard.service;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.EmbeddingConfig;
import com.traceguard.service.impl.OpenAiEmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAiEmbeddingClient 单元测试（GAP-004 验证点 1）
 * 批量分批（17 条 -> 2 批）、超时重试、维度基准校验、available() 判定。
 */
@DisplayName("OpenAI 兼容 Embedding 客户端单元测试")
class EmbeddingServiceTest {

    private OpenAiEmbeddingClient buildClient(int batchSize, int timeoutSeconds) throws Exception {
        LlmProperties props = new LlmProperties();
        EmbeddingConfig cfg = new EmbeddingConfig();
        cfg.setProvider("openai");
        cfg.setBaseUrl("https://api.example.com");
        cfg.setApiKey("sk-test");
        cfg.setModel("text-embedding-v3");
        cfg.setBatchSize(batchSize);
        cfg.setTimeoutSeconds(timeoutSeconds);
        props.setEmbedding(cfg);
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(props);
        RestTemplate rest = mock(RestTemplate.class);
        Field f = OpenAiEmbeddingClient.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(client, rest);
        // 重置维度基准（反射）
        Field dim = OpenAiEmbeddingClient.class.getDeclaredField("baselineDim");
        dim.setAccessible(true);
        dim.setInt(client, -1);
        return client;
    }

    private Map<String, Object> embeddingResponse(float[][] vectors) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            List<Double> embedding = new ArrayList<>();
            for (float v : vectors[i]) embedding.add((double) v);
            item.put("embedding", embedding);
            data.add(item);
        }
        resp.put("data", data);
        return resp;
    }

    @Test
    @DisplayName("available()：api-key 缺失返回 false，配置齐全返回 true")
    void availableDependsOnConfig() throws Exception {
        LlmProperties props = new LlmProperties();
        EmbeddingConfig cfg = new EmbeddingConfig();
        cfg.setBaseUrl("https://api.example.com");
        cfg.setApiKey("");
        cfg.setModel("m");
        props.setEmbedding(cfg);
        OpenAiEmbeddingClient missingKey = new OpenAiEmbeddingClient(props);
        assertThat(missingKey.available()).isFalse();

        OpenAiEmbeddingClient configured = buildClient(16, 30);
        assertThat(configured.available()).isTrue();
    }

    @Test
    @DisplayName("17 条文本按 batch-size=16 分 2 批调用，返回 17 个向量")
    void batchSplitsIntoTwoRequests() throws Exception {
        OpenAiEmbeddingClient client = buildClient(16, 30);
        RestTemplate rest = mock(RestTemplate.class);
        Field f = OpenAiEmbeddingClient.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(client, rest);
        float[][] first = new float[16][3];
        float[][] second = new float[1][3];
        for (int i = 0; i < 16; i++) first[i] = new float[]{0.1f, 0.2f, 0.3f};
        second[0] = new float[]{0.1f, 0.2f, 0.3f};
        when(rest.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(embeddingResponse(first), embeddingResponse(second));

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 17; i++) texts.add("text" + i);
        List<float[]> vectors = client.embedBatch(texts);

        assertThat(vectors).hasSize(17);
        assertThat(vectors).allSatisfy(v -> assertThat(v).isNotNull());
        verify(rest, times(2)).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("维度基准校验：首批 dim=3，后续批次 dim=5 视为失败（置 null）")
    void dimensionBaselineMismatchFailsBatch() throws Exception {
        OpenAiEmbeddingClient client = buildClient(2, 30);
        RestTemplate rest = mock(RestTemplate.class);
        Field f = OpenAiEmbeddingClient.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(client, rest);
        float[][] first = new float[][]{{0.1f, 0.2f, 0.3f}, {0.4f, 0.5f, 0.6f}};
        float[][] second = new float[][]{{0.1f, 0.2f, 0.3f, 0.4f, 0.5f}, {0.1f, 0.2f, 0.3f, 0.4f, 0.5f}};
        when(rest.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(embeddingResponse(first), embeddingResponse(second));

        List<float[]> vectors = client.embedBatch(Arrays.asList("a", "b", "c", "d"));
        assertThat(vectors.get(0)).isNotNull();
        assertThat(vectors.get(1)).isNotNull();
        // 第二批维度不一致 -> null
        assertThat(vectors.get(2)).isNull();
        assertThat(vectors.get(3)).isNull();
    }

    @Test
    @DisplayName("失败重试 1 次：首次调用抛异常，重试成功返回向量")
    void retryOnceOnFailure() throws Exception {
        OpenAiEmbeddingClient client = buildClient(2, 30);
        RestTemplate rest = mock(RestTemplate.class);
        Field f = OpenAiEmbeddingClient.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(client, rest);
        when(rest.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"))
                .thenReturn(embeddingResponse(new float[][]{{0.1f, 0.2f, 0.3f}, {0.1f, 0.2f, 0.3f}}));

        List<float[]> vectors = client.embedBatch(Arrays.asList("a", "b"));
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).isNotNull();
        assertThat(vectors.get(1)).isNotNull();
        verify(rest, times(2)).postForObject(anyString(), any(), eq(Map.class));
    }
}
