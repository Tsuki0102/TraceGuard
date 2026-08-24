package com.traceguard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流服务（需求5.2.2 访问安全）：
 * 按账号维度累计连续登录失败次数，达到阈值后临时锁定账号，
 * 锁定期间的登录尝试直接拒绝；登录成功即清零。
 * 状态保存在内存中（重启即重置），失败与锁定事件均留痕（日志+审计）。
 */
@Service
public class LoginAttemptService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginAttemptService.class);

    /** 最大连续失败次数，达到后锁定账号 */
    @Value("${traceguard.login.max-attempts:5}")
    int maxAttempts = 5;

    /** 锁定时长（分钟） */
    @Value("${traceguard.login.lock-minutes:10}")
    int lockMinutes = 10;

    @Autowired
    private AuditService auditService;

    /** username -> 失败记录 */
    private final Map<String, AttemptInfo> attempts = new ConcurrentHashMap<>();

    /** 连续失败计数与锁定截止时间（epoch ms，0 表示未锁定） */
    private static class AttemptInfo {
        int failCount;
        long lockUntil;
    }

    /**
     * 登录前检查账号是否处于锁定期，锁定中则抛出带剩余时间的异常
     */
    public void checkLocked(String username) {
        if (username == null) {
            return;
        }
        AttemptInfo info = attempts.get(username);
        if (info == null || info.lockUntil <= 0) {
            return;
        }
        long remaining = info.lockUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            // 锁定期已过，自动解除（失败计数保留，再次失败将立即重新锁定）
            info.lockUntil = 0;
            return;
        }
        long minutes = (remaining + 60_000 - 1) / 60_000;
        throw new RuntimeException("登录失败次数过多，账号已临时锁定，请约 " + minutes + " 分钟后再试");
    }

    /**
     * 记录一次登录失败；达到阈值时锁定账号并留痕（日志+审计）
     */
    public void recordFailure(String username) {
        if (username == null) {
            return;
        }
        AttemptInfo info = attempts.compute(username, (k, old) -> {
            AttemptInfo cur = old != null ? old : new AttemptInfo();
            cur.failCount++;
            if (cur.failCount >= maxAttempts) {
                cur.lockUntil = System.currentTimeMillis() + lockMinutes * 60_000L;
            }
            return cur;
        });
        if (info.lockUntil > 0 && info.failCount == maxAttempts) {
            LOGGER.warn("账号[{}]连续登录失败 {} 次，已锁定 {} 分钟（需求5.2.2 访问安全）",
                    username, maxAttempts, lockMinutes);
            // 触发锁定事件写入审计日志留痕
            auditService.record(null, username, "登录失败锁定", "POST", "/api/auth/login",
                    null, null, 429, null,
                    "连续登录失败 " + maxAttempts + " 次，账号锁定 " + lockMinutes + " 分钟");
        } else {
            LOGGER.info("账号[{}]登录失败，连续失败 {} 次（最多允许 {} 次）",
                    username, info.failCount, maxAttempts);
        }
    }

    /**
     * 登录成功后清空失败记录
     */
    public void clear(String username) {
        if (username != null) {
            attempts.remove(username);
        }
    }

    /** 查询账号当前连续失败次数（测试与排查用） */
    public int getFailCount(String username) {
        AttemptInfo info = attempts.get(username);
        return info == null ? 0 : info.failCount;
    }

    /** 查询账号是否处于锁定期（测试与排查用） */
    public boolean isLocked(String username) {
        AttemptInfo info = attempts.get(username);
        return info != null && info.lockUntil > System.currentTimeMillis();
    }
}
