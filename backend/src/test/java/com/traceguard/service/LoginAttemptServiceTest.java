package com.traceguard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败限流单元测试（需求5.2.2 访问安全）：
 * 连续失败达到阈值锁定、锁定期拒绝登录、成功清零、到期自动解除
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
        // 纯单元测试不依赖Spring容器：注入mock审计服务、固定限流参数
        ReflectionTestUtils.setField(service, "auditService", Mockito.mock(AuditService.class));
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "lockMinutes", 10);
    }

    @Test
    @DisplayName("未达阈值不锁定：4次失败后仍可登录")
    void notLockedBeforeThreshold() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("alice");
        }
        assertThat(service.getFailCount("alice")).isEqualTo(4);
        assertThat(service.isLocked("alice")).isFalse();
        // 未锁定时checkLocked不抛异常
        service.checkLocked("alice");
    }

    @Test
    @DisplayName("连续失败5次触发锁定，锁定期登录被拒绝并提示剩余时间")
    void lockedAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("bob");
        }
        assertThat(service.isLocked("bob")).isTrue();
        assertThatThrownBy(() -> service.checkLocked("bob"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("账号已临时锁定")
                .hasMessageContaining("分钟");
    }

    @Test
    @DisplayName("登录成功清零失败计数，之后单次失败不会立即锁定")
    void successResetsCounter() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("carol");
        }
        service.clear("carol");
        assertThat(service.getFailCount("carol")).isZero();
        service.recordFailure("carol");
        assertThat(service.isLocked("carol")).isFalse();
    }

    @Test
    @DisplayName("锁定期结束自动解除，可再次尝试登录")
    void lockExpiresAutomatically() {
        // 锁定时长设为0分钟：lockUntil=当前时间，checkLocked判定已到期并解除
        ReflectionTestUtils.setField(service, "lockMinutes", 0);
        for (int i = 0; i < 5; i++) {
            service.recordFailure("dave");
        }
        // lockUntil已不超过当前时间，视为到期解除
        service.checkLocked("dave");
        assertThat(service.isLocked("dave")).isFalse();
    }

    @Test
    @DisplayName("不同账号之间失败计数相互独立")
    void independentAccounts() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("eve");
        }
        assertThat(service.isLocked("eve")).isTrue();
        assertThat(service.isLocked("frank")).isFalse();
        service.checkLocked("frank");
    }
}
