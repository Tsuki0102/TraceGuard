package com.traceguard.util;

import com.traceguard.entity.AuditLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;

/**
 * 审计日志哈希链工具（AUD-08，FR-PLAT-004 防篡改）
 * 每条审计记录在写入时计算 curHash = SHA-256(prevHash | 规范化业务字段)，形成时间序链式摘要；
 * 校验时按序重算并与存储值比对，任何对历史记录的篡改都会破坏链路，定位首条异常记录。
 */
public class HashChainUtil {

    /** 链头固定前缀（无前驱时使用） */
    public static final String GENESIS = "TRACE-GUARD-AUDIT-GENESIS";

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HashChainUtil() {
    }

    /** 计算单条记录的规范串（参与哈希的业务字段，排除哈希本身） */
    public static String canonical(AuditLog log, String prevHash) {
        StringBuilder sb = new StringBuilder();
        sb.append(prevHash == null ? GENESIS : prevHash).append('|');
        sb.append(log.getUserId() == null ? "" : log.getUserId()).append('|');
        sb.append(log.getUsername() == null ? "" : log.getUsername()).append('|');
        sb.append(log.getOperation() == null ? "" : log.getOperation()).append('|');
        sb.append(log.getMethod() == null ? "" : log.getMethod()).append('|');
        sb.append(log.getPath() == null ? "" : log.getPath()).append('|');
        sb.append(log.getParams() == null ? "" : log.getParams()).append('|');
        sb.append(log.getIp() == null ? "" : log.getIp()).append('|');
        sb.append(log.getStatusCode() == null ? "" : log.getStatusCode()).append('|');
        sb.append(log.getCostMs() == null ? "" : log.getCostMs()).append('|');
        sb.append(log.getSuccess() == null ? "" : log.getSuccess()).append('|');
        sb.append(log.getErrorMsg() == null ? "" : log.getErrorMsg()).append('|');
        sb.append(log.getCreateTime() == null ? "" : log.getCreateTime().format(DTF));
        return sb.toString();
    }

    /** 计算 curHash */
    public static String computeCurHash(AuditLog log, String prevHash) {
        return sha256Hex(canonical(log, prevHash));
    }

    /** 校验单条记录（给定上一链的 curHash） */
    public static boolean verify(AuditLog log, String prevHash) {
        if (log.getCurHash() == null) {
            return false;
        }
        return computeCurHash(log, prevHash).equals(log.getCurHash());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
