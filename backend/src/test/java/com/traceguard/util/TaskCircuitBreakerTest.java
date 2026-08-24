package com.traceguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TST-03：TaskCircuitBreaker 任务级熔断器行为测试（GAP-022 熔断要素）。
 * 覆盖：连续失败达阈值打开、成功重置计数、打开后保持、reset 复位、阈值下限。
 */
@DisplayName("TST-03 TaskCircuitBreaker 熔断器")
class TaskCircuitBreakerTest {

    @Test
    @DisplayName("连续失败达阈值即打开熔断")
    void openAfterThresholdFailures() {
        TaskCircuitBreaker cb = new TaskCircuitBreaker(3);
        assertThat(cb.isOpen()).isFalse();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isFalse();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("未熔断时成功重置失败计数")
    void successResetsFailures() {
        TaskCircuitBreaker cb = new TaskCircuitBreaker(3);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertThat(cb.getConsecutiveFailures()).isZero();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    @DisplayName("熔断打开后成功不关闭，失败不累计")
    void openStateSticky() {
        TaskCircuitBreaker cb = new TaskCircuitBreaker(2);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue();

        cb.recordSuccess(); // 打开后成功不关闭熔断（等待任务级 reset）
        assertThat(cb.isOpen()).isTrue();

        int before = cb.getConsecutiveFailures();
        cb.recordFailure(); // 打开后失败不再累计
        assertThat(cb.getConsecutiveFailures()).isEqualTo(before);
    }

    @Test
    @DisplayName("reset 复位熔断与计数")
    void resetClears() {
        TaskCircuitBreaker cb = new TaskCircuitBreaker(2);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue();
        cb.reset();
        assertThat(cb.isOpen()).isFalse();
        assertThat(cb.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("阈值下限为 1（非法输入取 1）")
    void thresholdFloor() {
        TaskCircuitBreaker cb = new TaskCircuitBreaker(0);
        assertThat(cb.isOpen()).isFalse();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue(); // 阈值为 1，一次失败即打开
    }
}
