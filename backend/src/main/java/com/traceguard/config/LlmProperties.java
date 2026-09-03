package com.traceguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型编排配置（GAP-021 重构）
 * 多 provider + 按环节路由 + 模型表 + 限流/熔断参数。
 * 旧单模型字段（baseUrl/apiKey/model/timeoutSeconds）被结构化配置替代（GAP-021 计划删除）。
 * 运行时可改配置存于 tg_llm_config 表，启动/保存时覆盖本 yml 配置（api_key 加密存储）。
 */
@Component
@ConfigurationProperties(prefix = "traceguard.llm")
public class LlmProperties {

    /** 是否启用大模型增强（默认关闭，配置密钥并开启后生效） */
    private boolean enabled = false;

    /** provider 配置表（键：deepseek/glm/local） */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    /** 环节 -> provider 键路由表 */
    private Map<String, String> routing = new LinkedHashMap<>();

    /** 环节 -> model 名表 */
    private Map<String, String> models = new LinkedHashMap<>();

    /** 并发限流（同时进行的 chat 调用数） */
    private int maxConcurrentCalls = 4;

    /** 单任务单环节调用上限（性能保护） */
    private int maxCallsPerStage = 200;

    /** tg_llm_config 表 api_key 加密密钥 */
    private String configEncryptKey = "";

    /**
     * LLM 增强引擎（GAP-044）：self=OpenAI 兼容客户端（默认）；langchain=langchain4j 编排（需依赖可用）。
     * 不可用时（无依赖/下游异常）由 ConsistencyJudge 自动回退 self 引擎。
     */
    private String engine = "self";

    /** Embedding 配置段（GAP-004） */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /** GAP-046 候选复核配置段：LLM 二审不逐条全量执行，按候选规划收敛（性能基准报告 §5.2） */
    private CandidateReview candidateReview = new CandidateReview();

    public CandidateReview getCandidateReview() { return candidateReview; }
    public void setCandidateReview(CandidateReview candidateReview) { this.candidateReview = candidateReview; }

    /** GAP-046：候选复核参数（热配置经 SystemConfigController 同名键覆盖时以此为准） */
    public static class CandidateReview {
        /** 是否启用候选复核（false=退回全量逐条二审，仅评测对比时使用） */
        private boolean enabled = true;
        /** 复核硬上限（万行保守上限 200 对 × ≈2.1s/对 ≈ 7min 二审预算） */
        private int maxCandidates = 200;
        /** 明确一致池抽检比例（质量探针，0-1；0=关闭探针） */
        private double consistentSampleRate = 0.10;
        /** 探针缺陷率 >= 该值时升级补审剩余一致池（仍受 maxCandidates 约束） */
        private double escalateDefectRate = 0.10;
        /** 高风险对复核阈值（与 ConsistencyJudge.JUDGE_ARBITER_RISK_THRESHOLD 对齐） */
        private double highRiskThreshold = 0.30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxCandidates() { return maxCandidates; }
        public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
        public double getConsistentSampleRate() { return consistentSampleRate; }
        public void setConsistentSampleRate(double consistentSampleRate) { this.consistentSampleRate = consistentSampleRate; }
        public double getEscalateDefectRate() { return escalateDefectRate; }
        public void setEscalateDefectRate(double escalateDefectRate) { this.escalateDefectRate = escalateDefectRate; }
        public double getHighRiskThreshold() { return highRiskThreshold; }
        public void setHighRiskThreshold(double highRiskThreshold) { this.highRiskThreshold = highRiskThreshold; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public Map<String, String> getRouting() { return routing; }
    public void setRouting(Map<String, String> routing) { this.routing = routing; }
    public Map<String, String> getModels() { return models; }
    public void setModels(Map<String, String> models) { this.models = models; }
    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
    public int getMaxCallsPerStage() { return maxCallsPerStage; }
    public void setMaxCallsPerStage(int maxCallsPerStage) { this.maxCallsPerStage = maxCallsPerStage; }
    public String getConfigEncryptKey() { return configEncryptKey; }
    public void setConfigEncryptKey(String configEncryptKey) { this.configEncryptKey = configEncryptKey; }
    public EmbeddingConfig getEmbedding() { return embedding; }
    public void setEmbedding(EmbeddingConfig embedding) { this.embedding = embedding; }

    /** 当前生效的 LLM 增强引擎（self | langchain），见 {@link #engine} */
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    /** 单 provider 配置 */
    public static class ProviderConfig {
        private String baseUrl = "";
        private String apiKey = "";
        private int timeoutSeconds = 60;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /** Embedding 配置段（GAP-004） */
    public static class EmbeddingConfig {
        private String provider = "openai";   // openai | local
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "text-embedding-v3";
        private int batchSize = 16;
        private int timeoutSeconds = 30;
        /** AUD-05：本地 ONNX 模型文件路径（provider=local 时必填，如 ./models/bge-small-zh-v1.5/onnx/model.onb） */
        private String modelPath = "";
        /** AUD-05：本地模型输出向量维度（bge-small 系列为 512，bge-base 为 768，依模型而定） */
        private int modelDim = 512;
        /** AUD-05：是否对输出向量做 L2 归一化（BGE 默认 true） */
        private boolean normalize = true;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getModelPath() { return modelPath; }
        public void setModelPath(String modelPath) { this.modelPath = modelPath; }
        public int getModelDim() { return modelDim; }
        public void setModelDim(int modelDim) { this.modelDim = modelDim; }
        public boolean isNormalize() { return normalize; }
        public void setNormalize(boolean normalize) { this.normalize = normalize; }
    }
}
