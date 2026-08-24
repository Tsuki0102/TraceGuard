package com.traceguard.service;

import com.traceguard.config.LlmProperties;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.LlmChain;
import com.traceguard.llm.ModelRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 编排层装配（GAP-021）
 * 将 ModelRouter / LlmCallExecutor / LlmChain 装配为 Spring Bean，供 LlmService 与 AnalysisService 使用。
 * 运行时配置更新时通过 refresh() 重建（ModelRouter 清空 client 缓存）。
 */
@Configuration
public class LlmOrchestrator {

    private LlmProperties properties;
    private ModelRouter router;
    private LlmCallExecutor executor;
    private LlmChain chain;

    @Bean
    public ModelRouter modelRouter(LlmProperties llmProperties) {
        this.properties = llmProperties;
        this.router = new ModelRouter(llmProperties);
        return router;
    }

    @Bean
    public LlmCallExecutor llmCallExecutor(ModelRouter modelRouter) {
        this.executor = new LlmCallExecutor(properties, modelRouter);
        return executor;
    }

    @Bean
    public LlmChain llmChain(LlmCallExecutor llmCallExecutor) {
        this.chain = new LlmChain(llmCallExecutor);
        return chain;
    }

    /** 运行时配置更新后刷新 client 缓存 */
    public void refresh() {
        if (router != null) {
            router.refreshClients();
        }
    }
}
