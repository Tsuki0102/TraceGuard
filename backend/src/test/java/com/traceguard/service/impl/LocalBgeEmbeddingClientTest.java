package com.traceguard.service.impl;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.EmbeddingConfig;
import com.traceguard.service.EmbeddingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalBgeEmbeddingClient 单元测试（AUD-05，GAP-004，provider=local）
 * 验证「未配置 model-path / 模型缺失时自动回退规则模式」的安全降级路径，以及 EmbeddingConfig 装配选择。
 */
@DisplayName("本地 BGE 向量化客户端单元测试")
class LocalBgeEmbeddingClientTest {

    private LlmProperties propsWith(String modelPath, int dim) {
        LlmProperties props = new LlmProperties();
        EmbeddingConfig ec = new EmbeddingConfig();
        ec.setProvider("local");
        ec.setModelPath(modelPath);
        ec.setModelDim(dim);
        ec.setNormalize(true);
        props.setEmbedding(ec);
        return props;
    }

    @Test
    @DisplayName("未配置 model-path 时 available()=false（安全回退规则模式）")
    void noModelPathUnavailable() {
        EmbeddingService svc = new LocalBgeEmbeddingClient(propsWith("", 512));
        assertThat(svc.available()).isFalse();
        assertThat(svc.embed("任意中文文本")).isNull();
    }

    @Test
    @DisplayName("model-path 指向不存在文件时 available()=false（不阻断分析）")
    void missingModelFileUnavailable() {
        EmbeddingService svc = new LocalBgeEmbeddingClient(propsWith("./models/not-exist/model.onnx", 512));
        assertThat(svc.available()).isFalse();
        assertThat(svc.embedBatch(java.util.List.of("a", "b"))).hasSize(2)
                .allSatisfy(v -> assertThat(v).isNull());
    }
}
