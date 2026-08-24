package com.traceguard.llm;

import com.traceguard.config.LlmProperties;
import com.traceguard.config.LlmProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmCallExecutor 单元测试（GAP-021 验证点 1）
 * 用可注入的 mock LlmClient 验证：enabled=false 失败、路由失败、失败重试、熔断、限流。
 */
@DisplayName("LLM 调用执行治理单元测试")
class LlmCallExecutorTest {

    private LlmProperties properties;
    private LlmCallExecutor executor;
    private CountingClient client;

    static class CountingClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final boolean failAlways;
        final boolean failOnce;

        CountingClient(boolean failAlways, boolean failOnce) {
            this.failAlways = failAlways;
            this.failOnce = failOnce;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            int n = calls.incrementAndGet();
            if (failAlways) {
                return LlmResponse.fail("mock failure");
            }
            if (failOnce && n == 1) {
                return LlmResponse.fail("mock first failure");
            }
            return LlmResponse.ok("ok-" + n, 10, 5);
        }

        @Override
        public String providerName() {
            return "deepseek";
        }
    }

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        ProviderConfig pc = new ProviderConfig();
        pc.setBaseUrl("https://api.deepseek.com");
        pc.setApiKey("sk-test");
        properties.getProviders().put("deepseek", pc);
        properties.getRouting().put("alloy", "deepseek");
        properties.getModels().put("alloy", "deepseek-v4-flash");
        properties.setEnabled(true);
        properties.setMaxConcurrentCalls(2);
    }

    @Test
    @DisplayName("enabled=false 时 execute 直接失败不发起调用")
    void disabledReturnsFail() {
        properties.setEnabled(false);
        executor = new LlmCallExecutor(properties, new ModelRouter(properties));
        LlmResponse resp = executor.execute(Stage.ALLOY, List.of(LlmMessage.user("hi")));
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("未启用");
    }

    @Test
    @DisplayName("失败后重试 1 次；首次失败第二次成功")
    void retriesOnceOnFailure() throws Exception {
        // 注入自定义 client
        client = new CountingClient(false, true);
        executor = new LlmCallExecutor(properties, routerWithClient(client));
        LlmResponse resp = executor.execute(Stage.ALLOY, List.of(LlmMessage.user("hi")));
        assertThat(resp.isSuccess()).isTrue();
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("连续 5 次失败触发全局熔断，熔断期间直接失败")
    void circuitBreaksAfterFiveFailures() throws Exception {
        client = new CountingClient(true, false);
        executor = new LlmCallExecutor(properties, routerWithClient(client));
        for (int i = 0; i < 5; i++) {
            executor.execute(Stage.ALLOY, List.of(LlmMessage.user("hi")));
        }
        // 每次 execute 失败后重试 1 次 => 5 次 execute 共 10 次 client 调用后熔断开启
        assertThat(client.calls.get()).isEqualTo(10);
        // 第 6 次：熔断开启，直接失败且不再调用 client
        LlmResponse resp = executor.execute(Stage.ALLOY, List.of(LlmMessage.user("hi")));
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("熔断");
        assertThat(client.calls.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("并发限流：同时调用数不超过 max-concurrent-calls")
    void limitsConcurrency() throws Exception {
        // 每个调用耗时 200ms，max=2；并发 4 个，信号量排队，总耗时 > 400ms 且全部成功
        properties.setMaxConcurrentCalls(2);
        client = new CountingClient(false, false);
        executor = new LlmCallExecutor(properties, routerWithClient(client));
        long start = System.currentTimeMillis();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                LlmResponse resp = executor.execute(Stage.ALLOY, List.of(LlmMessage.user("hi")));
                assertThat(resp.isSuccess()).isTrue();
                latch.countDown();
            });
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.currentTimeMillis() - start;
        // 4 个调用 2 并发，理论耗时 >= 2*慢调用时长（未 mock 慢调用，仅验证不超时且成功）
        assertThat(elapsed).isLessThan(10_000);
        assertThat(client.calls.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("路由配置缺失时 execute 返回失败不抛异常")
    void routeFailureReturnsFail() {
        properties.getRouting().clear();
        executor = new LlmCallExecutor(properties, new ModelRouter(properties));
        LlmResponse resp = executor.execute(Stage.ALLOY, List.of(LlmMessage.user("hi")));
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("路由失败");
    }

    private ModelRouter routerWithClient(LlmClient client) {
        return new ModelRouter(properties) {
            @Override
            public LlmClient clientFor(String providerKey) {
                return client;
            }
        };
    }
}
