package com.traceguard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * JWT 吊销黑名单服务（SEC-16）。
 *
 * 登出时将 token 的 jti 加入 tg_token_blacklist，LoginInterceptor 每请求校验是否已吊销，
 * 使被登出的 token 在自然过期前立即失效。表记录携带 expire_time，超过即失效，
 * 在读写时惰性清理过期行（避免表无限膨胀）。
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    /** 惰性清理触发概率（每次读写按 1/N 概率执行一次清理，控制 DB 压力） */
    private static final int CLEANUP_RATE = 100;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 将 token 的 jti 加入黑名单，直到其自然过期 */
    public void revoke(String jti, long expireAtMillis) {
        if (jti == null || jti.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO tg_token_blacklist (jti, expire_time) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE expire_time = VALUES(expire_time)",
                    jti, new java.sql.Timestamp(expireAtMillis));
        } catch (Exception e) {
            // 黑名单写入失败不阻断登出主流程，但必须可观测
            log.error("JWT 加入黑名单失败 jti={}", jti, e);
        }
        maybeCleanup();
    }

    /** 判断 token 的 jti 是否已被吊销且未过期 */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        maybeCleanup();
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tg_token_blacklist WHERE jti = ? AND expire_time > NOW()",
                    Integer.class, jti);
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("查询 JWT 黑名单失败 jti={}", jti, e);
            return false;
        }
    }

    /** 惰性清理已过期黑名单记录（按概率触发，避免每请求执行 DELETE） */
    private void maybeCleanup() {
        if (ThreadLocalRandomHolder.random(CLEANUP_RATE)) {
            try {
                jdbcTemplate.update("DELETE FROM tg_token_blacklist WHERE expire_time <= NOW()");
            } catch (Exception e) {
                log.debug("清理 JWT 黑名单过期记录失败", e);
            }
        }
    }

    /** 轻量随机占位（避免引入额外依赖） */
    private static final class ThreadLocalRandomHolder {
        static boolean random(int bound) {
            return java.util.concurrent.ThreadLocalRandom.current().nextInt(bound) == 0;
        }
    }
}
