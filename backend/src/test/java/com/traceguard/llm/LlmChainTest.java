package com.traceguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmChain 单元测试（GAP-021 验证点 3）
 */
@DisplayName("LLM 链式调用单元测试")
class LlmChainTest {

    private LlmChain chain;
    private ScriptedClient client;

    static class ScriptedClient implements LlmClient {
        final java.util.Queue<LlmResponse> script = new java.util.ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        void add(String content) {
            script.add(LlmResponse.ok(content, 1, 1));
        }

        void addFail() {
            script.add(LlmResponse.fail("mock fail"));
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls.incrementAndGet();
            LlmResponse r = script.poll();
            return r != null ? r : LlmResponse.fail("script empty");
        }

        @Override
        public String providerName() {
            return "glm";
        }
    }

    @BeforeEach
    void setUp() {
        LlmProperties properties = new LlmProperties();
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        pc.setApiKey("sk-test");
        properties.getProviders().put("glm", pc);
        properties.getRouting().put("requirement", "glm");
        properties.getRouting().put("alloy", "glm");
        properties.getModels().put("requirement", "glm-5.3");
        properties.getModels().put("alloy", "glm-alloy");
        properties.setEnabled(true);
        client = new ScriptedClient();
        ModelRouter router = new ModelRouter(properties) {
            @Override
            public LlmClient clientFor(String providerKey) {
                return client;
            }
        };
        LlmCallExecutor executor = new LlmCallExecutor(properties, router);
        chain = new LlmChain(executor);
    }

    @Test
    @DisplayName("链式调用产出 Kripke JSON 与剥离围栏的 Alloy 代码")
    void chainProducesKripkeAndAlloy() {
        client.add("{\"states\":[{\"name\":\"open\"}],\"transitions\":[],\"constraints\":[],\"invariants\":[]}");
        client.add("```alloy\nmodule spec\nsig Order {}\ncheck x for 3\n```");
        LlmChain.ChainResult result = chain.requirementToAlloy("订单状态约束");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getKripke()).isNotNull();
        assertThat(result.getKripke().path("states").path(0).path("name").asText()).isEqualTo("open");
        assertThat(result.getAlloyCode()).contains("module spec").doesNotContain("```");
    }

    @Test
    @DisplayName("第二步未产出有效 Alloy 代码触发反馈重试")
    void retriesOnBlankAlloyOutput() {
        // 第一步成功
        client.add("{\"states\":[]}");
        // 第二步：先输出空白（无代码可提取，触发反馈重试），再给合法代码
        client.add("   ");
        client.add("```alloy\nmodule retry\nsig A {}\ncheck ok for 2\n```");
        LlmChain.ChainResult result = chain.requirementToAlloy("重试场景");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAlloyCode()).contains("module retry");
    }

    @Test
    @DisplayName("重试仍失败返回失败标记")
    void failsAfterRetries() {
        client.add("{\"states\":[]}");
        client.add("   ");
        client.add("   ");
        LlmChain.ChainResult result = chain.requirementToAlloy("失败场景");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("stripFences 剥离带语言标记与无标记的代码围栏")
    void stripFencesVariants() {
        assertThat(LlmChain.stripFences("```alloy\nmodule a\n```")).isEqualTo("module a");
        assertThat(LlmChain.stripFences("```\nmodule b\n```")).isEqualTo("module b");
        assertThat(LlmChain.stripFences("module c")).isEqualTo("module c");
    }

    @Test
    @DisplayName("parseJson 从非纯 JSON 响应中截取 JSON 对象")
    void parseJsonExtractsObject() {
        JsonNode node = LlmChain.parseJson("好的，结果如下：\n{\"reason\":\"原因\",\"suggestion\":\"建议\"}\n完毕");
        assertThat(node).isNotNull();
        assertThat(node.path("reason").asText()).isEqualTo("原因");
    }
}
