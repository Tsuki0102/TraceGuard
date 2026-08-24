package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_audit_log")
public class AuditLog extends BaseEntity {
    private Long userId;
    private String username;
    private String operation;
    private String method;
    private String path;
    private String params;
    private String ip;
    private Integer statusCode;
    private Long costMs;
    private Integer success;
    private String errorMsg;

    /**
     * 哈希链：前一条日志的 cur_hash（AUD-08 审计日志防篡改）。首条为链头（固定前缀）。
     */
    private String prevHash;

    /**
     * 哈希链：本条日志基于 prevHash + 业务字段计算的 SHA-256 摘要（AUD-08）。校验时重算比对即可发现篡改。
     */
    private String curHash;
}
