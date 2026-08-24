package com.traceguard.util;

/**
 * 任务级熔断器（GAP-022 统一"开关+超时+熔断+兜底"四要素中的熔断）
 * 按分析任务维度隔离：任务开始时创建，任务结束销毁。
 * 单任务内连续失败达阈值后打开，本任务后续调用直接走降级路径。
 */
public class TaskCircuitBreaker {

    private final int threshold;
    private int consecutiveFailures = 0;
    private boolean open = false;

    public TaskCircuitBreaker(int threshold) {
        this.threshold = Math.max(1, threshold);
    }

    /** 记录一次成功：重置连续失败计数（熔断已开启时保持打开，等待任务级重置） */
    public synchronized void recordSuccess() {
        if (!open) {
            consecutiveFailures = 0;
        }
    }

    /** 记录一次失败：连续失败达阈值即打开熔断 */
    public synchronized void recordFailure() {
        if (open) {
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            open = true;
        }
    }

    /** 熔断是否已打开（打开后本任务后续调用直接走降级） */
    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** 任务结束或需重新计数时重置 */
    public synchronized void reset() {
        consecutiveFailures = 0;
        open = false;
    }
}
