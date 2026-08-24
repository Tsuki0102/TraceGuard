package com.traceguard.eval;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.ModelRouter;
import com.traceguard.service.ConsistencyJudge;

/**
 * AUD-02 + GAP-046：评测期 LLM 语义判定构造器（支持真集成 CodeLlama / LangChain 演示）。
 *
 * 环境变量：
 *   LLM_PROVIDER：qwen（默认，云端千问）| codellama（GAP-043 本地 Ollama CodeLlama）
 *   LLM_ENGINE ：self（默认，OpenAI 兼容客户端）| langchain（GAP-044 langchain4j 编排）
 *   QWEN_API_KEY / llm.qwen.apiKey / %TMP%\qwen-key.txt：qwen 密钥
 *   CODELLAMA_BASE_URL（默认 http://localhost:11434/v1）、CODELLAMA_MODEL（默认 codellama:7b）
 *
 * 无可用 provider / 密钥时返回 null（评测降级 engine=rule）。
 * 密钥仅用于本次运行进程，不写入任何持久化文件。
 */
public final class LlmJudgeFactory {

    private LlmJudgeFactory() {
    }

    public static ConsistencyJudge build() {
        String provider = System.getenv("LLM_PROVIDER");
        if (provider == null || provider.trim().isEmpty()) {
            provider = System.getProperty("llm.provider", "qwen");
        }
        String engine = System.getenv("LLM_ENGINE");
        if (engine == null || engine.trim().isEmpty()) {
            engine = System.getProperty("llm.engine", "self");
        }

        if ("codellama".equalsIgnoreCase(provider)) {
            return buildCodeLlama(engine);
        }
        return buildQwen(engine);
    }

    /** qwen（云端 OpenAI 兼容）：self 引擎走 LlmCallExecutor；langchain 引擎仍以 qwen 端点为 langchain4j 后端 */
    private static ConsistencyJudge buildQwen(String engine) {
        String key = System.getenv("QWEN_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            key = System.getProperty("llm.qwen.apiKey", "");
        }
        if (key == null || key.trim().isEmpty()) {
            try {
                java.io.File keyFile = new java.io.File(System.getProperty("java.io.tmpdir"), "qwen-key.txt");
                if (keyFile.isFile()) {
                    key = new String(java.nio.file.Files.readAllBytes(keyFile.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (key.startsWith("\uFEFF")) {
                        key = key.substring(1);
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        // 模型名：QWEN_MODEL 环境变量 / llm.qwen.model 属性可覆盖，默认 qwen-max（与 .env 的 QWEN_MODEL 联动）
        String model = System.getenv("QWEN_MODEL");
        if (model == null || model.trim().isEmpty()) {
            model = System.getProperty("llm.qwen.model", "qwen-max");
        }
        return assemble(baseUrl, key, model, engine);
    }

    /** GAP-043：本地 Ollama CodeLlama（OpenAI 兼容端点）；langchain 引擎由 langchain4j 编排同一 CodeLlama */
    private static ConsistencyJudge buildCodeLlama(String engine) {
        String baseUrl = System.getenv("CODELLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = System.getProperty("llm.codellama.baseUrl", "http://localhost:11434/v1");
        }
        String model = System.getenv("CODELLAMA_MODEL");
        if (model == null || model.trim().isEmpty()) {
            model = System.getProperty("llm.codellama.model", "codellama:7b");
        }
        // Ollama 不校验密钥，占位即可
        return assemble(baseUrl, "ollama", model, engine);
    }

    private static ConsistencyJudge assemble(String baseUrl, String apiKey, String model, String engine) {
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        ProviderConfig p = new ProviderConfig();
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setTimeoutSeconds(120); // 本地 CodeLlama 推理较慢，放宽超时保证 LLM 判定成功
        String name = "eval-" + model.replaceAll("[^a-zA-Z0-9]", "-");
        props.getProviders().put(name, p);
        props.getRouting().put("consistency-check", name);
        props.getModels().put("consistency-check", model);
        props.setMaxConcurrentCalls(4);
        props.setMaxCallsPerStage(500);
        ModelRouter router = new ModelRouter(props);
        LlmCallExecutor executor = new LlmCallExecutor(props, router);

        if ("langchain".equalsIgnoreCase(engine)) {
            // GAP-044：langchain4j 编排同一后端（CodeLlama / qwen）作为 LangChain 引擎
            com.traceguard.llm.LangChainAdapter adapter =
                    com.traceguard.llm.LangChainAdapter.create(baseUrl, model, 120);
            return new ConsistencyJudge(executor, ConsistencyJudge.Engine.LANGCHAIN, adapter);
        }
        return new ConsistencyJudge(executor);
    }
}
