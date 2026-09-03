package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import com.traceguard.entity.LlmCallLog;
import com.traceguard.entity.LlmConfig;
import com.traceguard.llm.LlmChain;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.LlmClient;
import com.traceguard.llm.LlmMessage;
import com.traceguard.llm.LlmResponse;
import com.traceguard.llm.LangChainAdapter;
import com.traceguard.llm.ModelRouter;
import com.traceguard.llm.Stage;
import com.traceguard.mapper.LlmCallLogMapper;
import com.traceguard.mapper.LlmConfigMapper;
import com.traceguard.util.LlmConfigCryptoUtil;
import com.traceguard.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大模型业务门面（GAP-021 重构）
 * 保留既有公共 API：getStatus() / testConnection() / explainDefect(...)；
 * 私有 chat() 的 RestTemplate 直调逻辑删除，改走 LlmCallExecutor 编排层。
 * 新增 4 个业务方法（供 GAP-001 接入）：analyzeRequirement / generateAlloy / describeCode / explainDefect。
 * 同时承载 tg_llm_config 运行时配置的读写（api_key 加密存储）。
 */
@Service
public class LlmService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private LlmProperties properties;

    @Autowired
    private LlmCallExecutor executor;

    @Autowired
    private ModelRouter router;

    @Autowired
    private LlmChain llmChain;

    @Autowired
    private LlmConfigMapper llmConfigMapper;

    @Autowired
    private LlmOrchestrator orchestrator;

    @Autowired
    private LlmCallLogMapper llmCallLogMapper;

    /** 是否已启用（总开关 + 至少一个可用 provider）
     * 可用判定：云端 provider 需配置 api-key；本地模型（Ollama/vLLM，base-url 指向本机且无需密钥）api-key 为空也视为可用（GAP-043）。 */
    public boolean isEnabled() {
        if (!properties.isEnabled()) {
            return false;
        }
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null || providers.isEmpty()) {
            return false;
        }
        for (ProviderConfig pc : providers.values()) {
            if (pc.getApiKey() != null && !pc.getApiKey().isEmpty()) {
                return true;
            }
            // 本地模型：base-url 指向本机（localhost/127.0.0.1）即视为可用，无需 api-key
            String base = pc.getBaseUrl();
            if (base != null && (base.contains("localhost") || base.contains("127.0.0.1"))) {
                return true;
            }
        }
        return false;
    }

    /** 单任务单环节调用上限（GAP-001 阶段配额，性能保护） */
    public int getMaxCallsPerStage() {
        return properties != null ? properties.getMaxCallsPerStage() : 200;
    }

    /** GAP-046：候选复核配置（一致性二审候选规划参数），properties 缺失时返回默认值 */
    public com.traceguard.config.LlmProperties.CandidateReview getCandidateReview() {
        return properties != null && properties.getCandidateReview() != null
                ? properties.getCandidateReview()
                : new com.traceguard.config.LlmProperties.CandidateReview();
    }

    // ==================== GAP-021 四业务方法（GAP-001 接入） ====================

    /** 需求语义提取：需求文本 -> Kripke 结构（states/transitions/constraints/invariants），失败返回 null */
    public JsonNode analyzeRequirement(String requirementText) {
        if (!isEnabled()) {
            return null;
        }
        String system = "你是软件需求分析专家。请将需求文本建模为 Kripke 结构，严格输出 JSON（不要 markdown 围栏），"
                + "格式：{\"states\":[{\"name\":\"...\",\"description\":\"...\"}],"
                + "\"transitions\":[{\"from\":\"...\",\"to\":\"...\",\"condition\":\"...\"}],"
                + "\"constraints\":[\"...\"],\"invariants\":[\"...\"]}";
        LlmResponse resp = executor.execute(Stage.REQUIREMENT,
                List.of(LlmMessage.system(system), LlmMessage.user(requirementText)));
        if (!resp.isSuccess() || resp.getContent() == null) {
            return null;
        }
        return LlmChain.parseJson(resp.getContent());
    }

    /** Alloy 规约生成：需求文本 + Kripke JSON -> Alloy 代码（剥离 markdown 围栏），失败返回 null */
    public String generateAlloy(String requirementText, Object kripke) {
        if (!isEnabled()) {
            return null;
        }
        String kripkeJson = "{}";
        if (kripke != null) {
            try {
                kripkeJson = kripke instanceof JsonNode ? ((JsonNode) kripke).toString() : MAPPER.writeValueAsString(kripke);
            } catch (Exception e) {
                kripkeJson = kripke.toString();
            }
        }
        String system = "你是形式化验证专家。请基于需求文本与 Kripke 结构生成**可被结构校验器解析**的 Alloy 6 规约，"
                + "必须严格遵循以下模板形态（否则校验失败）：\n"
                + "1) 以 `module <reqId下划线形式>` 开头（如 module req_0001）；\n"
                + "2) 实体签名固定为 `sig <Type> { id: Int, state: one State, createdAt: Time }`，并声明 "
                + "`abstract sig State {}` + 每个状态 `one sig <StateName> extends State {}`，以及 `sig Time {}`；\n"
                + "3) 初始状态谓词固定为 `pred init[t: Time] { some o: <Type> | o.state = <InitState> and o.createdAt = t }`；\n"
                + "4) 状态转移谓词固定为 `pred transition[t, t': Time] { all o: <Type> | o.createdAt = t implies "
                + "(o.state = A and o.state' = B or ...) }`（A/B 须取自 Kripke 状态集）；\n"
                + "5) 不变量用 `fact invariants { ... }`；\n"
                + "6) 以 `assert consistencyCheck { ... }` + `check consistencyCheck for 5` 结尾；\n"
                + "7) 状态名使用首字母大写的 Kripke 状态名（如 Initial/Processing/Failed），不要自行发明新状态；\n"
                + "8) 输出放在 ```alloy 代码块内，只输出代码，不要任何解释文字。";
        String user = "需求文本：\n" + requirementText + "\n\nKripke 结构 JSON：\n" + kripkeJson;
        LlmResponse resp = executor.execute(Stage.ALLOY,
                List.of(LlmMessage.system(system), LlmMessage.user(user)));
        if (!resp.isSuccess() || resp.getContent() == null) {
            return null;
        }
        return LlmChain.stripFences(resp.getContent());
    }

    /** 代码逻辑描述：方法代码 + CFG 摘要 -> 中文逻辑描述，失败返回 null */
    public String describeCode(String methodCode, String cfgSummary) {
        if (!isEnabled()) {
            return null;
        }
        String code = methodCode != null && methodCode.length() > 4000
                ? methodCode.substring(0, 4000) : methodCode;
        String system = "你是 Java 代码分析专家，用中文准确描述方法业务逻辑，"
                + "覆盖：输入输出、主要分支、循环、异常处理、资源管理；不超过 200 字，不要逐行翻译代码。";
        String user = "方法代码:\n" + (code == null ? "" : code)
                + "\nCFG 摘要:\n" + (cfgSummary == null ? "" : cfgSummary);
        LlmResponse resp = executor.execute(Stage.CODE_EXPLAIN,
                List.of(LlmMessage.system(system), LlmMessage.user(user)));
        if (!resp.isSuccess() || resp.getContent() == null) {
            return null;
        }
        String desc = resp.getContent().replaceAll("```", "").trim();
        return desc.length() > 500 ? desc.substring(0, 500) : desc;
    }

    /** 缺陷解释增强：返回 {reason, suggestion}，失败返回 null */
    public Map<String, String> explainDefect(String requirementText, String codeSnippet, String defectType) {
        if (!isEnabled()) {
            return null;
        }
        String system = "你是代码审查专家。请分析缺陷产生原因并给出修复建议，严格输出 JSON（不要 markdown 围栏），"
                + "格式：{\"reason\":\"...\",\"suggestion\":\"...\"}";
        String user = "缺陷类型：" + (defectType == null ? "" : defectType)
                + "\n需求文本：" + (requirementText == null ? "" : requirementText)
                + "\n代码片段：" + (codeSnippet == null ? "" : codeSnippet.substring(0, Math.min(500, codeSnippet.length())));
        LlmResponse resp = executor.execute(Stage.DEFECT_EXPLAIN,
                List.of(LlmMessage.system(system), LlmMessage.user(user)));
        if (!resp.isSuccess() || resp.getContent() == null) {
            return null;
        }
        JsonNode node = LlmChain.parseJson(resp.getContent());
        if (node == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("reason", node.path("reason").asText(""));
        result.put("suggestion", node.path("suggestion").asText(""));
        return result;
    }

    /** 链式：需求 -> Kripke -> Alloy */
    public LlmChain.ChainResult requirementToAlloy(String requirementText) {
        if (!isEnabled()) {
            return LlmChain.ChainResult.fail("LLM 未启用");
        }
        return llmChain.requirementToAlloy(requirementText);
    }

    /** AUD-02：需求-代码一致性语义判定（LLM 二审），失败返回 null（保留规则判定）
     * GAP-044：当 traceguard.llm.engine=langchain 时构造 LangChain 适配器（指向一致性判定环节路由到的 provider，
     * 本地 CodeLlama 即通过此路径由 langchain4j 编排），不可用时 ConsistencyJudge 自动回退 self 引擎。 */
    public ConsistencyJudge.Judgement judgeConsistency(String requirementText, String codeSnippet,
                                                       double semanticSimilarity, double constraintMatch,
                                                       double invariantSatisfaction, double totalSimilarity,
                                                       String ruleDefectType) {
        return judgeConsistency(requirementText, codeSnippet, semanticSimilarity, constraintMatch,
                invariantSatisfaction, totalSimilarity, ruleDefectType, null, Double.NaN);
    }

    /**
     * FUN-04b：一致性判定统一入口。提供类级证据时走双判定管线（交叉验证 + 规则仲裁 + 数值归属过滤）；
     * 未提供证据或管线不可用时退化为与旧口径一致的单阶段判定。
     *
     * @param ctx 类级判定证据（同类方法分工清单 + 常量定义），可空
     * @param ruleRisk 规则链路缺陷风险分（CodeDefectPatternDetector），用于分歧仲裁；NaN 表示不可用
     */
    public ConsistencyJudge.Judgement judgeConsistency(String requirementText, String codeSnippet,
                                                       double semanticSimilarity, double constraintMatch,
                                                       double invariantSatisfaction, double totalSimilarity,
                                                       String ruleDefectType,
                                                       ConsistencyJudge.JudgeContext ctx, double ruleRisk) {
        if (!isEnabled()) {
            return null;
        }
        ConsistencyJudge judge = buildConsistencyJudge();
        if (ctx == null || ctx.isEmpty()) {
            return judge.judge(requirementText, codeSnippet, semanticSimilarity, constraintMatch,
                    invariantSatisfaction, totalSimilarity, ruleDefectType);
        }
        ConsistencyJudge.Judgement dual = judge.judgeDual(requirementText, codeSnippet,
                semanticSimilarity, constraintMatch, invariantSatisfaction, totalSimilarity,
                ruleDefectType, ctx, ruleRisk);
        return dual != null ? dual
                : judge.judge(requirementText, codeSnippet, semanticSimilarity, constraintMatch,
                        invariantSatisfaction, totalSimilarity, ruleDefectType);
    }

    /** 构造一致性判定组件（self 引擎默认；langchain 引擎复用一致性环节路由的 provider，GAP-043/044） */
    private ConsistencyJudge buildConsistencyJudge() {
        ConsistencyJudge.Engine engine = ConsistencyJudge.Engine.SELF;
        LangChainAdapter langChain = null;
        if ("langchain".equalsIgnoreCase(properties.getEngine())) {
            engine = ConsistencyJudge.Engine.LANGCHAIN;
            try {
                // 取一致性判定环节路由到的 provider 配置，作为 LangChain 编排后端（GAP-043 指向本地 CodeLlama）
                String providerKey = properties.getRouting() != null
                        ? properties.getRouting().get(Stage.CONSISTENCY_CHECK.getConfigKey()) : null;
                ProviderConfig pc = providerKey != null && properties.getProviders() != null
                        ? properties.getProviders().get(providerKey) : null;
                if (pc != null && pc.getBaseUrl() != null && !pc.getBaseUrl().isEmpty()) {
                    String model = properties.getModels() != null
                            ? properties.getModels().get(Stage.CONSISTENCY_CHECK.getConfigKey()) : null;
                    int timeout = pc.getTimeoutSeconds() > 0 ? pc.getTimeoutSeconds() : 120;
                    langChain = LangChainAdapter.create(pc.getBaseUrl(), model != null ? model : "", timeout);
                } else {
                    LOGGER.warn("GAP-044：未找到一致性判定环节 provider 配置，回退 self 引擎。");
                    engine = ConsistencyJudge.Engine.SELF;
                }
            } catch (Exception e) {
                LOGGER.warn("GAP-044：构造 LangChain 适配器失败，回退 self 引擎：{}", e.getMessage());
                engine = ConsistencyJudge.Engine.SELF;
                langChain = null;
            }
        }
        return new ConsistencyJudge(executor, engine, langChain);
    }

    // ==================== 既有公共 API（保留） ====================

    /** W2-08：AI 助手问答（单轮无状态，含系统提示词；仅管理员入口，防配额滥用） */
    public String chat(String message) {
        if (!isEnabled() || message == null || message.trim().isEmpty()) {
            return null;
        }
        try {
            ModelRouter.RoutedTarget target = router.route(Stage.CODE_EXPLAIN);
            com.traceguard.llm.LlmRequest q = new com.traceguard.llm.LlmRequest();
            q.setModel(target.getModel());
            q.setMessages(List.of(
                    LlmMessage.system(CHAT_SYSTEM_PROMPT),
                    LlmMessage.user(message.trim())
            ));
            q.setTemperature(0.3);
            LlmResponse resp = target.getClient().chat(q);
            return resp.isSuccess() ? resp.getContent() : null;
        } catch (Exception e) {
            LOGGER.warn("AI 助手对话失败: {}", e.getMessage());
            return null;
        }
    }

    private static final String CHAT_SYSTEM_PROMPT =
            "你是 TraceGuard（软件需求-代码一致性验证与缺陷自动检测系统）的 AI 助手。"
            + "你可以解答关于需求解析、代码解析、缺陷检测、一致性验证、追溯矩阵、报告导出"
            + "以及软件开发与质量保证方面的问题。回答请简洁、准确、使用中文。";

    /** 连通性测试：返回模型简单回复 */
    public String testConnection() {
        if (!isEnabled()) {
            return null;
        }
        try {
            ModelRouter.RoutedTarget target = router.route(Stage.CODE_EXPLAIN);
            com.traceguard.llm.LlmRequest q = new com.traceguard.llm.LlmRequest();
            q.setModel(target.getModel());
            q.setMessages(List.of(LlmMessage.user("请回复：连接成功")));
            q.setTemperature(0.1);
            LlmResponse resp = target.getClient().chat(q);
            return resp.isSuccess() ? resp.getContent() : null;
        } catch (Exception e) {
            LOGGER.warn("LLM 连通性测试失败: {}", e.getMessage());
            return null;
        }
    }

    /** 按 provider 维度连通测试（POST /llm/test，admin） */
    public Map<String, Object> testProviderConnection(String providerKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            LlmClient client = router.clientFor(providerKey);
            com.traceguard.llm.LlmRequest q = new com.traceguard.llm.LlmRequest();
            q.setModel(modelForProvider(providerKey));
            q.setMessages(List.of(LlmMessage.user("请回复：连接成功")));
            q.setTemperature(0.1);
            LlmResponse resp = client.chat(q);
            result.put("provider", providerKey);
            result.put("success", resp.isSuccess());
            result.put("reply", resp.isSuccess() ? resp.getContent() : resp.getErrorMessage());
        } catch (Exception e) {
            result.put("provider", providerKey);
            result.put("success", false);
            result.put("reply", e.getMessage());
        }
        return result;
    }

    private String firstModel() {
        Map<String, String> models = properties.getModels();
        if (models != null && !models.isEmpty()) {
            return models.values().iterator().next();
        }
        return "unknown";
    }

    /** 取路由到指定服务商的第一个环节所配模型；无则回退 firstModel */
    private String modelForProvider(String providerKey) {
        Map<String, String> routing = properties.getRouting();
        Map<String, String> models = properties.getModels();
        if (routing != null && models != null) {
            for (Map.Entry<String, String> e : routing.entrySet()) {
                if (providerKey.equals(e.getValue()) && models.get(e.getKey()) != null
                        && !models.get(e.getKey()).isEmpty()) {
                    return models.get(e.getKey());
                }
            }
        }
        return firstModel();
    }

    // ==================== tg_llm_config 运行时配置读写（GAP-021 步骤 8） ====================

    /**
     * 读取运行时配置：有表数据则以 DB 配置覆盖 yml（api_key 解密），返回脱敏视图（api_key 仅尾 4 位）。
     */
    public Map<String, Object> getRuntimeConfig() {
        Map<String, Object> view = new LinkedHashMap<>();
        LlmConfig cfg = llmConfigMapper.selectById(1L);
        boolean fromDb = cfg != null;
        view.put("source", fromDb ? "db" : "yml");
        boolean enabled = fromDb ? Boolean.TRUE.equals(cfg.getEnabled()) : properties.isEnabled();
        view.put("enabled", enabled);
        view.put("maxConcurrentCalls", properties.getMaxConcurrentCalls());
        view.put("maxCallsPerStage", properties.getMaxCallsPerStage());

        // providers（api_key 脱敏）
        Map<String, Object> providersView = new LinkedHashMap<>();
        Map<String, String> providersJson = fromDb ? parseJsonMap(cfg.getProvidersJson()) : null;
        for (Map.Entry<String, ProviderConfig> e : properties.getProviders().entrySet()) {
            Map<String, Object> pv = new LinkedHashMap<>();
            String apiKey = fromDb ? (providersJson != null ? providersJson.get(e.getKey() + ".api-key") : null) : e.getValue().getApiKey();
            if (apiKey != null && LlmConfigCryptoUtil.isEncrypted(apiKey) && properties.getConfigEncryptKey() != null) {
                try {
                    apiKey = LlmConfigCryptoUtil.decrypt(apiKey, properties.getConfigEncryptKey());
                } catch (Exception ex) {
                    LOGGER.warn("LLM 配置 api_key 解密失败: {}", ex.getMessage());
                }
            }
            pv.put("baseUrl", fromDb && providersJson != null
                    ? providersJson.getOrDefault(e.getKey() + ".base-url", e.getValue().getBaseUrl())
                    : e.getValue().getBaseUrl());
            pv.put("apiKeyMasked", maskKey(apiKey));
            pv.put("apiKeyConfigured", apiKey != null && !apiKey.isEmpty());
            pv.put("timeoutSeconds", e.getValue().getTimeoutSeconds());
            providersView.put(e.getKey(), pv);
        }
        view.put("providers", providersView);

        // routing / models
        view.put("routing", fromDb ? parseJsonMap(cfg.getRoutingJson()) : new LinkedHashMap<>(properties.getRouting()));
        view.put("models", fromDb ? parseJsonMap(cfg.getModelsJson()) : new LinkedHashMap<>(properties.getModels()));
        view.put("embedding", properties.getEmbedding() != null
                ? MAPPER.convertValue(properties.getEmbedding(), Map.class) : null);
        return view;
    }

    /**
     * 保存运行时配置（admin）：providers 中 api_key 加密后连同 routing/models 落库，
     * 覆盖 yml 并刷新 client 缓存。
     * providers 结构：[{provider, baseUrl, apiKey}]；routing/models 为 {stage: value}。
     */
    public void saveRuntimeConfig(boolean enabled, List<Map<String, String>> providers,
                                  Map<String, String> routing, Map<String, String> models) {
        if (properties.getConfigEncryptKey() == null || properties.getConfigEncryptKey().isEmpty()) {
            throw new com.traceguard.common.BusinessException(400, "LLM 配置加密密钥未设置（traceguard.llm.config-encrypt-key）");
        }
        Map<String, String> providersJson = new LinkedHashMap<>();
        // 更新内存配置
        for (Map<String, String> p : providers) {
            String name = p.get("provider");
            if (name == null || name.isEmpty()) {
                continue;
            }
            ProviderConfig pc = properties.getProviders().computeIfAbsent(name, k -> new ProviderConfig());
            String baseUrl = p.get("baseUrl");
            String apiKey = p.get("apiKey");
            if (apiKey != null) {
                apiKey = apiKey.trim(); // 去除控制台复制带入的首尾空白/换行
            }
            if (baseUrl != null) {
                pc.setBaseUrl(baseUrl);
                providersJson.put(name + ".base-url", baseUrl);
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                try {
                    String encrypted = LlmConfigCryptoUtil.encrypt(apiKey, properties.getConfigEncryptKey());
                    // 内存 ProviderConfig 需保持明文（client 直接作为 Bearer token 使用），DB 存密文
                    pc.setApiKey(apiKey);
                    providersJson.put(name + ".api-key", encrypted);
                } catch (Exception e) {
                    throw new com.traceguard.common.BusinessException(400, "api_key 加密失败: " + e.getMessage());
                }
            }
        }
        // 支持删除：移除不在本次提交清单中的 provider（运行期生效，yml 默认项重启后仍会回填）
        Set<String> keep = new HashSet<>();
        for (Map<String, String> p : providers) {
            String name = p.get("provider");
            if (name != null && !name.isEmpty()) {
                keep.add(name);
            }
        }
        properties.getProviders().keySet().removeIf(k -> !keep.contains(k));
        if (routing != null) {
            properties.setRouting(new LinkedHashMap<>(routing));
        }
        if (models != null) {
            properties.setModels(new LinkedHashMap<>(models));
        }
        properties.setEnabled(enabled);

        // 落库（单行，id 恒为 1）
        LlmConfig cfg = llmConfigMapper.selectById(1L);
        if (cfg == null) {
            cfg = new LlmConfig();
            cfg.setId(1L);
            cfg.setEnabled(enabled);
            cfg.setProvidersJson(toJson(providersJson));
            cfg.setRoutingJson(toJson(properties.getRouting()));
            cfg.setModelsJson(toJson(properties.getModels()));
            cfg.setUpdateTime(LocalDateTime.now());
            cfg.setUpdateBy(UserContext.getUserId());
            llmConfigMapper.insert(cfg);
        } else {
            cfg.setEnabled(enabled);
            cfg.setProvidersJson(toJson(providersJson));
            cfg.setRoutingJson(toJson(properties.getRouting()));
            cfg.setModelsJson(toJson(properties.getModels()));
            cfg.setUpdateTime(LocalDateTime.now());
            cfg.setUpdateBy(UserContext.getUserId());
            llmConfigMapper.updateById(cfg);
        }
        orchestrator.refresh();
    }

    /** 应用 DB 配置覆盖 yml（启动时调用，api_key 解密后注入 client 工厂） */
    /** W5：LLM 用量统计（大模型配置页用量区块）：总量/成功率/均耗时 + 按天 + 按场景 */
    public Map<String, Object> usage(int days) {
        int n = Math.min(Math.max(days, 1), 90);
        LocalDateTime since = LocalDateTime.now().minusDays(n);
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        long success = 0;
        long latencySum = 0;
        Map<String, long[]> byDay = new LinkedHashMap<>();
        Map<String, long[]> byScene = new LinkedHashMap<>();
        try {
            List<LlmCallLog> logs = llmCallLogMapper.selectList(
                    new QueryWrapper<LlmCallLog>().ge("create_time", since).orderByAsc("create_time").last("LIMIT 20000"));
            for (LlmCallLog g : logs) {
                total++;
                boolean ok = g.getSuccess() != null && g.getSuccess() == 1;
                if (ok) success++;
                if (g.getLatencyMs() != null) latencySum += g.getLatencyMs();
                String day = g.getCreateTime() == null ? "-" : g.getCreateTime().toLocalDate().toString();
                byDay.computeIfAbsent(day, k -> new long[2])[0]++;
                if (ok) byDay.get(day)[1]++;
                String scene = g.getScene() == null ? "unknown" : g.getScene();
                byScene.computeIfAbsent(scene, k -> new long[2])[0]++;
                if (!ok) byScene.get(scene)[1]++;
            }
        } catch (Exception e) {
            LOGGER.warn("LLM 用量统计失败（返回零值）: {}", e.getMessage());
        }
        out.put("days", n);
        out.put("total", total);
        out.put("success", success);
        out.put("failed", total - success);
        out.put("successRate", total == 0 ? 0 : Math.round(success * 1000.0 / total) / 10.0);
        out.put("avgLatencyMs", total == 0 ? 0 : latencySum / total);
        List<Map<String, Object>> dayRows = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> en : byDay.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", en.getKey());
            r.put("total", en.getValue()[0]);
            r.put("success", en.getValue()[1]);
            dayRows.add(r);
        }
        out.put("byDay", dayRows);
        List<Map<String, Object>> sceneRows = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> en : byScene.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scene", en.getKey());
            r.put("total", en.getValue()[0]);
            r.put("failed", en.getValue()[1]);
            sceneRows.add(r);
        }
        out.put("byScene", sceneRows);
        return out;
    }

    public void applyDbConfigIfPresent() {
        try {
            LlmConfig cfg = llmConfigMapper.selectById(1L);
            if (cfg == null) {
                return;
            }
            properties.setEnabled(Boolean.TRUE.equals(cfg.getEnabled()));
            Map<String, String> providersJson = parseJsonMap(cfg.getProvidersJson());
            if (providersJson != null) {
                for (Map.Entry<String, String> e : providersJson.entrySet()) {
                    String key = e.getKey();
                    String value = e.getValue();
                    if (!key.contains(".")) {
                        continue;
                    }
                    String provider = key.substring(0, key.indexOf('.'));
                    String field = key.substring(key.indexOf('.') + 1);
                    ProviderConfig pc = properties.getProviders().computeIfAbsent(provider, k -> new ProviderConfig());
                    if ("base-url".equals(field)) {
                        pc.setBaseUrl(value);
                    } else if ("api-key".equals(field)) {
                        try {
                            pc.setApiKey(LlmConfigCryptoUtil.decrypt(value, properties.getConfigEncryptKey()));
                        } catch (Exception ex) {
                            LOGGER.warn("LLM 配置 api_key 解密失败: {}", ex.getMessage());
                        }
                    }
                }
            }
            Map<String, String> routing = parseJsonMap(cfg.getRoutingJson());
            if (routing != null && !routing.isEmpty()) {
                properties.setRouting(routing);
            }
            Map<String, String> models = parseJsonMap(cfg.getModelsJson());
            if (models != null && !models.isEmpty()) {
                properties.setModels(models);
            }
        } catch (Exception e) {
            LOGGER.warn("应用 DB LLM 配置失败: {}", e.getMessage());
        }
    }

    private Map<String, String> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            Map<String, String> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(),
                    entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString()));
            return map;
        } catch (Exception e) {
            LOGGER.warn("LLM 配置 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }
}
