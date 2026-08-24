package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class User extends BaseEntity {
    private String username;
    private String password;
    private String realName;
    private String email;
    private String role;
    private LocalDateTime lastLoginTime;
    /** GAP-027：首次登录或管理员重置后强制修改密码 */
    private Boolean mustChangePassword;

    /**
     * 密码最近一次修改时间（AUD-03 密码定期提醒，SRS 5.2.2 访问安全）。
     * 注册/修改密码时刷新；用于计算密码是否临近 90 天过期阈值。
     */
    private LocalDateTime passwordUpdatedAt;

    /**
     * 密码强制过期周期（天）：超过该时长未改密则建议更换（AUD-03，默认阈值=90）。
     */
    public static final int PASSWORD_EXPIRE_DAYS = 90;

    /**
     * 密码过期前提醒窗口（天）：剩余有效期不足该天数时，登录返回即将过期提醒（AUD-03）。
     */
    public static final int PASSWORD_EXPIRE_WARN_DAYS = 15;

    /**
     * 判断密码是否临近过期（AUD-03）：剩余有效期不足提醒窗口。
     * 无 passwordUpdatedAt 记录时视为安全（历史数据兼容）。
     */
    public boolean isPasswordExpiringSoon() {
        if (this.passwordUpdatedAt == null) {
            return false;
        }
        long daysSince = java.time.Duration.between(this.passwordUpdatedAt, LocalDateTime.now()).toDays();
        long daysLeft = (long) PASSWORD_EXPIRE_DAYS - daysSince;
        return daysLeft <= PASSWORD_EXPIRE_WARN_DAYS;
    }
}
