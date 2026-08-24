package com.traceguard.llm;

import com.traceguard.config.LlmProperties;
import com.traceguard.llm.ModelRouter.RoutedTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用统一执行治理（GAP-021 步骤 5）
 * 信号量并发限流 -> 路由 -> 超时调用 -> 指数退避重试 1 次 -> 全局熔断判定。
 * 熔断器与用量计数为进程级全局单例；任务+环节配额由外部（业务层）隔离。
 */
public class LlmCallExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmCallExecutor.class);

    /** 连续失败熔断阈值 */
    private static final int CIRCUIT_FAIL_THRESHOLD = 5;
    /** 熔断持续时间（毫秒） */
    private static final long CIRCUIT_OPEN_MS = 300_000;
    /** 并发限流获取超时（毫秒） */
    private static final long SEMAPHORE_TIMEOUT_MS = 30_000;
    /** 失败后指数退避基数（毫秒），重试 1 次 */
    private static final long RETRY_BACKOFF_MS = 2_000;

    private final LlmProperties properties;
    private final ModelRouter router;
    private final Semaphore semaphore;
    /** 全局调用数统计（支撑指标评测与成本核算） */
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalSuccess = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    /** 全局熔断器状态 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    public LlmCallExecutor(LlmProperties properties, ModelRouter router) {
        this.properties = properties;
        this.router = router;
        this.semaphore = new Semaphore(Math.max(1, properties.getMaxConcurrentCalls()));
    }

    public ModelRouter getRouter() {
        return router;
    }

    /**
     * 统一入口：限流 -> 路由 -> 超时调用 -> 重试 -> 熔断判定
     * 失败时返回 success=false，不抛异常。
     */
    public LlmResponse execute(Stage stage, List<LlmMessage> messages) {
        totalCalls.incrementAndGet();
        if (!properties.isEnabled()) {
            totalFailed.incrementAndGet();
            return LlmResponse.fail("LLM 未启用（traceguard.llm.enabled=false）");
        }
        if (isCircuitOpen()) {
            totalFailed.incrementAndGet();
            return LlmResponse.fail("LLM 全局熔断中（circuit open），走降级路径");
        }
        // 并发限流：获取超时视为失败
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(1, SEMAPHORE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            totalFailed.incrementAndGet();
            return LlmResponse.fail("LLM 并发限流排队超时（>" + SEMAPHORE_TIMEOUT_MS + "ms）");
        }
        try {
            RoutedTarget target;
            try {
                target = router.route(stage);
            } catch (Exception e) {
                recordFailure();
                totalFailed.incrementAndGet();
                return LlmResponse.fail("LLM 路由失败: " + e.getMessage());
            }
            LlmRequest request = buildRequest(target.getModel(), messages, stage);
            // 首次调用
            LlmResponse resp = callOnce(target, request);
            // 失败后指数退避重试 1 次
            if (!resp.isSuccess()) {
                sleepBackoff();
                resp = callOnce(target, request);
            }
            if (resp.isSuccess()) {
                totalSuccess.incrementAndGet();
                consecutiveFailures.set(0);
            } else {
                recordFailure();
                totalFailed.incrementAndGet();
            }
            return resp;
        } finally {
            semaphore.release();
        }
    }

    private LlmResponse callOnce(RoutedTarget target, LlmRequest request) {
        try {
            return target.getClient().chat(request);
        } catch (Exception e) {
            return LlmResponse.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private LlmRequest buildRequest(String model, List<LlmMessage> messages, Stage stage) {
        LlmRequest request = new LlmRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setTemperature(0.3);
        // 一致性判定要求模型输出结构化 JSON（consistent/defectType/reason），
        // 开启 jsonMode 强制本地模型(CodeLlama/Ollama)返回合法 JSON，避免自然语导致解析失败（GAP-049 实测 CodeLlama 在非 jsonMode 下 95% 返回自然语言）。
        // REQUIREMENT（Kripke JSON）/ CONSISTENCY_CHECK（判定 JSON）要求结构化 JSON 输出；
        // ALLOY 生成的是 Alloy 6 代码（要求放在 ```alloy 代码块内），非 JSON，故不开启 jsonMode，
        // 否则模型被强制输出 JSON 会导致 Alloy 代码无法解析（GAP-052 复测发现：开启后 45/45 全部校验失败）。
        request.setJsonMode(stage == Stage.REQUIREMENT || stage == Stage.CONSISTENCY_CHECK);
        if (stage == Stage.CONSISTENCY_CHECK) {
            // AUD-02：限制一致性判定输出长度，加快生成并避免超长输出挂起
            request.setMaxTokens(200);
        }
        return request;
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= CIRCUIT_FAIL_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
            LOGGER.warn("LLM 全局熔断开启（连续 {} 次失败），持续 {}ms", CIRCUIT_FAIL_THRESHOLD, CIRCUIT_OPEN_MS);
        }
    }

    private boolean isCircuitOpen() {
        if (circuitOpenUntil > System.currentTimeMillis()) {
            return true;
        }
        if (circuitOpenUntil > 0) {
            // 半开探测：过期后重置
            circuitOpenUntil = 0;
            consecutiveFailures.set(0);
        }
        return false;
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 重置全局统计（测试/运维） */
    public void resetStats() {
        totalCalls.set(0);
        totalSuccess.set(0);
        totalFailed.set(0);
    }

    public long getTotalCalls() { return totalCalls.get(); }
    public long getTotalSuccess() { return totalSuccess.get(); }
    public long getTotalFailed() { return totalFailed.get(); }
}
