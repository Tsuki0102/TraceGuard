package com.traceguard.llm;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按环节路由 provider + model（GAP-021 步骤 4）
 * routing/models 查不到时抛配置异常，上层按"LLM 不可用"降级。
 */
public class ModelRouter {

    /** 路由目标 */
    public static class RoutedTarget {
        private final LlmClient client;
        private final String model;

        public RoutedTarget(LlmClient client, String model) {
            this.client = client;
            this.model = model;
        }

        public LlmClient getClient() { return client; }
        public String getModel() { return model; }
    }

    private final LlmProperties properties;
    private final Map<String, LlmClient> clientCache = new ConcurrentHashMap<>();

    public ModelRouter(LlmProperties properties) {
        this.properties = properties;
    }

    /** 按环节解析目标 provider client 与 model 名 */
    public RoutedTarget route(Stage stage) {
        String stageKey = stage.getConfigKey();
        Map<String, String> routing = properties.getRouting();
        Map<String, String> models = properties.getModels();
        if (routing == null || models == null) {
            throw new IllegalStateException("LLM 路由表未配置（traceguard.llm.routing/models）");
        }
        String providerKey = routing.get(stageKey);
        String model = models.get(stageKey);
        if (providerKey == null || model == null || providerKey.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("环节[" + stage + "]未配置 provider 或 model（routing/models）");
        }
        return new RoutedTarget(clientFor(providerKey), model);
    }

    /** 获取/创建指定 provider 的 client（配置变更时按 provider 键重建） */
    public LlmClient clientFor(String providerKey) {
        return clientCache.computeIfAbsent(providerKey, key -> {
            Map<String, ProviderConfig> providers = properties.getProviders();
            ProviderConfig cfg = providers != null ? providers.get(key) : null;
            if (cfg == null || cfg.getBaseUrl() == null || cfg.getBaseUrl().isEmpty()) {
                throw new IllegalStateException("provider[" + key + "] 未配置（traceguard.llm.providers." + key + ".base-url）");
            }
            return new OpenAiLlmClient(key, cfg);
        });
    }

    /** 清除 client 缓存（运行期配置更新后调用，使新 api_key/baseUrl 生效） */
    public void refreshClients() {
        clientCache.clear();
    }
}
