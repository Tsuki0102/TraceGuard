package com.traceguard.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUD-03 密码定期提醒单元测试（SRS 5.2.2 访问安全）：
 * 验证 User.isPasswordExpiringSoon() 的 90 天过期口径与 15 天提醒窗口判定。
 */
class UserPasswordExpiryTest {

    @Test
    @DisplayName("无密码修改时间记录时不触发过期提醒（历史数据兼容）")
    void noRecordMeansNotExpiring() {
        User user = new User();
        user.setPasswordUpdatedAt(null);
        assertThat(user.isPasswordExpiringSoon()).isFalse();
    }

    @Test
    @DisplayName("密码刚修改（0 天）不触发过期提醒")
    void freshPasswordNotExpiring() {
        User user = new User();
        user.setPasswordUpdatedAt(LocalDateTime.now());
        assertThat(user.isPasswordExpiringSoon()).isFalse();
    }

    @Test
    @DisplayName("密码超过 90 天阈值触发过期提醒")
    void expiredPasswordTriggersWarn() {
        User user = new User();
        user.setPasswordUpdatedAt(LocalDateTime.now().minusDays(120));
        assertThat(user.isPasswordExpiringSoon()).isTrue();
    }

    @Test
    @DisplayName("密码剩余有效期不足 15 天（如已用 80 天）触发提醒")
    void withinWarnWindowTriggersWarn() {
        User user = new User();
        user.setPasswordUpdatedAt(LocalDateTime.now().minusDays(80));
        assertThat(user.isPasswordExpiringSoon()).isTrue();
    }

    @Test
    @DisplayName("密码使用 30 天（剩余 60 天）不触发提醒")
    void midLifeNotExpiring() {
        User user = new User();
        user.setPasswordUpdatedAt(LocalDateTime.now().minusDays(30));
        assertThat(user.isPasswordExpiringSoon()).isFalse();
    }

    @Test
    @DisplayName("密码使用 76 天（剩余 14 天）处于提醒窗口边界，触发提醒")
    void warnWindowBoundaryTriggersWarn() {
        User user = new User();
        user.setPasswordUpdatedAt(LocalDateTime.now().minusDays(76));
        assertThat(user.isPasswordExpiringSoon()).isTrue();
    }
}
