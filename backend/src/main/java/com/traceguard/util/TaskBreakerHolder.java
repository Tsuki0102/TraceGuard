package com.traceguard.util;

/**
 * 任务级熔断器 ThreadLocal 持有者（GAP-022）
 * AnalysisService 在任务开始时 set，任务结束 finally 中 clear，实现按任务维度隔离。
 * 未设置时 get() 返回 null，调用方视为"未熔断"（如单测与独立工具调用）。
 */
public final class TaskBreakerHolder {

    private static final ThreadLocal<TaskCircuitBreaker> HOLDER = new ThreadLocal<>();

    private TaskBreakerHolder() {
    }

    public static void set(TaskCircuitBreaker breaker) {
        HOLDER.set(breaker);
    }

    public static TaskCircuitBreaker get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
