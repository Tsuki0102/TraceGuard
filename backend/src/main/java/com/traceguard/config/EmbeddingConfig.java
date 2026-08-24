package com.traceguard.config;

import com.traceguard.service.EmbeddingService;
import com.traceguard.service.impl.LocalBgeEmbeddingClient;
import com.traceguard.service.impl.OpenAiEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AUD-05 / GAP-004：EmbeddingService 装配
 * 根据 {@code traceguard.llm.embedding.provider} 选择实现：
 * <ul>
 *   <li>{@code local}（默认）：本地 BGE ONNX 推理（零外部依赖、离线可用），由 LocalBgeEmbeddingClient 加载模型；
 *       模型未配置/缺失时 available()=false，上层自动回退规则模式。</li>
 *   <li>{@code openai}：云端 OpenAI 兼容嵌入；未配置可用 key 时 available()=false。</li>
 * </ul>
 * 之前缺失该装配导致 EmbeddingService 从未被注入（@Autowired(required=false) 为空），语义向量化恒被跳过。
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    @ConditionalOnMissingBean(EmbeddingService.class)
    public EmbeddingService embeddingService(LlmProperties properties) {
        String provider = properties.getEmbedding().getProvider();
        EmbeddingService svc;
        if ("local".equalsIgnoreCase(provider)) {
            svc = new LocalBgeEmbeddingClient(properties);
            LOGGER.info("EmbeddingService 装配：local（BGE ONNX），available={}", svc.available());
        } else {
            svc = new OpenAiEmbeddingClient(properties);
            LOGGER.info("EmbeddingService 装配：openai，available={}", svc.available());
        }
        return svc;
    }
}
