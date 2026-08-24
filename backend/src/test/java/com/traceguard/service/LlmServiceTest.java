package com.traceguard.service;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmService 单元测试：未启用时静默降级（GAP-021 重构后）
 */
@DisplayName("大模型服务单元测试")
class LlmServiceTest {

    private LlmService llmService;
    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        llmService = new LlmService();
        org.springframework.test.util.ReflectionTestUtils.setField(llmService, "properties", properties);
    }

    @Test
    @DisplayName("默认配置未启用，isEnabled返回false")
    void disabledByDefault() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(llmService.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("未启用时需求分析返回null不抛异常")
    void analyzeReturnsNullWhenDisabled() {
        assertThat(llmService.analyzeRequirement("用户登录需求")).isNull();
    }

    @Test
    @DisplayName("未启用时缺陷解释返回null不抛异常")
    void explainReturnsNullWhenDisabled() {
        assertThat(llmService.explainDefect("需求文本", "int a = 1;", "空指针风险")).isNull();
    }

    @Test
    @DisplayName("未启用时连通性测试返回null")
    void testConnectionNullWhenDisabled() {
        assertThat(llmService.testConnection()).isNull();
    }

    @Test
    @DisplayName("未启用时Alloy生成返回null")
    void generateAlloyNullWhenDisabled() {
        assertThat(llmService.generateAlloy("需求", null)).isNull();
    }

    @Test
    @DisplayName("未启用时代码描述返回null")
    void describeCodeNullWhenDisabled() {
        assertThat(llmService.describeCode("public void m() {}", null)).isNull();
    }

    @Test
    @DisplayName("enabled但所有provider缺少api-key时仍视为未启用")
    void enabledWithoutApiKeyIsDisabled() {
        properties.setEnabled(true);
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl("https://api.deepseek.com");
        pc.setApiKey("");
        properties.getProviders().put("deepseek", pc);
        assertThat(llmService.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("enabled且provider配置api-key后视为启用")
    void enabledWithApiKeyIsEnabled() {
        properties.setEnabled(true);
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl("https://api.deepseek.com");
        pc.setApiKey("sk-test");
        properties.getProviders().put("deepseek", pc);
        assertThat(llmService.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("GAP-043：本地模型（localhost，api-key 为空）也视为启用")
    void enabledWithLocalModelNoApiKeyIsEnabled() {
        properties.setEnabled(true);
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl("http://localhost:11434/v1");   // 本地 Ollama，无需密钥
        pc.setApiKey("");
        properties.getProviders().put("codellama", pc);
        assertThat(llmService.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("GAP-044：engine=langchain 但无本地模型时 judgeConsistency 不抛异常（回退 self 后降级 null）")
    void judgeConsistencyLangchainNoBackendReturnsNullSafely() {
        properties.setEnabled(true);
        properties.setEngine("langchain");
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl("http://localhost:11434/v1");
        pc.setApiKey("");
        pc.setTimeoutSeconds(5);
        properties.getProviders().put("codellama", pc);
        properties.getRouting().put("consistency-check", "codellama");
        properties.getModels().put("consistency-check", "codellama:7b");
        // 本测试无真实 Ollama，expected 为 null（LLM 不可用，保留规则判定），且不抛异常
        assertThat(llmService.judgeConsistency("需求", "code", 0.9, 0.8, 0.9, 0.85, "")).isNull();
    }
}
