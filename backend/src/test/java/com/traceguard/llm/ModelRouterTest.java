package com.traceguard.llm;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModelRouter 单元测试（GAP-021 验证点 2）
 */
@DisplayName("LLM 按环节路由单元测试")
class ModelRouterTest {

    private LlmProperties properties;
    private ModelRouter router;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        ProviderConfig deepseek = new ProviderConfig();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("sk-deepseek");
        ProviderConfig glm = new ProviderConfig();
        glm.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        glm.setApiKey("sk-glm");
        properties.getProviders().put("deepseek", deepseek);
        properties.getProviders().put("glm", glm);
        properties.getRouting().put("requirement", "glm");
        properties.getRouting().put("alloy", "deepseek");
        properties.getModels().put("requirement", "glm-5.3");
        properties.getModels().put("alloy", "deepseek-v4-flash");
        router = new ModelRouter(properties);
    }

    @Test
    @DisplayName("四环节分别路由到正确 provider+model")
    void routesToCorrectTarget() {
        properties.getRouting().put("code-explain", "deepseek");
        properties.getRouting().put("defect-explain", "deepseek");
        properties.getModels().put("code-explain", "deepseek-v4-flash");
        properties.getModels().put("defect-explain", "deepseek-v4-flash");

        ModelRouter.RoutedTarget requirement = router.route(Stage.REQUIREMENT);
        assertThat(requirement.getClient().providerName()).isEqualTo("glm");
        assertThat(requirement.getModel()).isEqualTo("glm-5.3");

        ModelRouter.RoutedTarget alloy = router.route(Stage.ALLOY);
        assertThat(alloy.getClient().providerName()).isEqualTo("deepseek");
        assertThat(alloy.getModel()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("配置缺失时抛出可识别异常")
    void missingConfigThrows() {
        assertThatThrownBy(() -> router.route(Stage.CODE_EXPLAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CODE_EXPLAIN");
    }

    @Test
    @DisplayName("provider 未配置 base-url 时抛出可识别异常")
    void missingProviderThrows() {
        properties.getRouting().put("code-explain", "nonexistent");
        properties.getModels().put("code-explain", "m");
        assertThatThrownBy(() -> router.route(Stage.CODE_EXPLAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    @DisplayName("refreshClients 后再次获取同一 provider 仍可用")
    void refreshClientsKeepsWorking() {
        router.refreshClients();
        ModelRouter.RoutedTarget target = router.route(Stage.ALLOY);
        assertThat(target.getClient().providerName()).isEqualTo("deepseek");
    }
}
