package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * GAP-017：用户备份口令配置实体
 * 存储用户的备份加密配置（salt、iterations、验证数据）
 * 使用 PBKDF2WithHmacSHA256 派生密钥，不存储明文口令
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_backup_config")
public class BackupConfig extends BaseEntity {

    /**
     * 用户ID（sys_user.id）
     */
    private Long userId;

    /**
     * PBKDF2 盐（Base64 编码）
     */
    private String salt;

    /**
     * PBKDF2 迭代次数（OWASP 推荐 310000）
     */
    private Integer iterations;

    /**
     * 验证数据：用派生密钥加密的固定字符串，用于验证口令正确性
     * 格式：[salt (16)][iterations (4)][IV (12)][密文]
     */
    private byte[] verifyData;

    /**
     * 是否已设置备份口令（true=已设置，false=未设置）
     */
    private Integer hasPassword;

    /**
     * 最后验证时间
     */
    private LocalDateTime lastVerifyTime;

    /**
     * 口令错误次数（连续错误 N 次后锁定）
     */
    private Integer failCount;

    /**
     * 锁定时间（解锁时间）
     */
    private LocalDateTime lockTime;
}