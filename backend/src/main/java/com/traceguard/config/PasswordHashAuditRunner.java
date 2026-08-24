package com.traceguard.config;

import com.traceguard.entity.User;
import com.traceguard.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * SEC-06：启动密码哈希审计。
 *
 * 扫描 sys_user 表，对以下账号执行强制重置：
 * - 密码非 BCrypt 哈希（明文/其他哈希残留）：替换为随机 BCrypt 密码（无法再登录），并置 must_change_password=1 强制改密；
 * - 密码为空或 null：置 must_change_password=1 强制改密（避免无凭据账号长期存在）。
 *
 * 目的：关闭明文兼容通道后，历史明文密码不得作为可登录凭据残留。
 * 安全原则：日志只输出用户名，不输出密码/哈希。
 */
@Component
@Slf4j
public class PasswordHashAuditRunner implements ApplicationRunner {

    /** BCrypt 哈希前缀（Spring Security 默认 $2a$） */
    private static final String BCRYPT_PREFIX = "$2a$";

    @Autowired
    private UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void run(ApplicationArguments args) {
        List<User> users;
        try {
            users = userMapper.selectList(null);
        } catch (Exception e) {
            // 数据库未就绪/表不存在时跳过（StartupDbCheck 已做前置探测，这里不阻断启动）
            log.warn("[PasswordHashAudit] 用户表扫描失败，跳过密码哈希审计: {}", e.getMessage());
            return;
        }
        int forced = 0;
        for (User user : users) {
            if (needsForce(user)) {
                resetPassword(user);
                forced++;
            }
        }
        if (forced > 0) {
            log.warn("[PasswordHashAudit] 共 {} 个账号密码非 BCrypt 哈希或为空，已强制重置并要求改密（SEC-06）", forced);
        } else {
            log.info("[PasswordHashAudit] 密码哈希审计通过：全部账号均为 BCrypt 密文");
        }
    }

    private boolean needsForce(User user) {
        String pwd = user.getPassword();
        return pwd == null || pwd.isEmpty() || !pwd.startsWith(BCRYPT_PREFIX);
    }

    private void resetPassword(User user) {
        // 生成 24 字节随机密码并 BCrypt 编码：该凭据不可预知、无法登录，仅占位防止空哈希
        byte[] random = new byte[24];
        secureRandom.nextBytes(random);
        String randomPlain = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(randomPlain));
        update.setMustChangePassword(true);       // 强制下次登录改密
        update.setPasswordUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.warn("[PasswordHashAudit] 账号[{}]密码哈希非 BCrypt，已强制重置并要求修改密码（SEC-06）", user.getUsername());
    }
}
