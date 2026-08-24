package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.entity.User;
import com.traceguard.mapper.UserMapper;
import com.traceguard.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LoginAttemptService loginAttemptService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户登录：校验密码并签发JWT Token
     * 仅接受 BCrypt 密文比对（SEC-06：已关闭明文密码兼容通道，非 $2a$ 哈希一律视为无效）
     * 登录失败限流（需求5.2.2）：连续失败达到阈值后临时锁定账号
     */
    public Map<String, Object> login(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new RuntimeException("用户名不能为空");
        }
        // 锁定期间直接拒绝，不进入密码校验（防暴力破解）
        loginAttemptService.checkLocked(username);
        User user;
        try {
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, username);
            user = userMapper.selectOne(wrapper);
            if (user == null) {
                throw new RuntimeException("用户不存在");
            }

            String storedPassword = user.getPassword();
            boolean matched;
            if (storedPassword != null && storedPassword.startsWith("$2a$")) {
                // 已是BCrypt密文，直接校验
                matched = passwordEncoder.matches(password, storedPassword);
            } else {
                // SEC-06：关闭明文密码兼容通道。DB 中非 BCrypt 哈希一律拒绝登录，
                // 由启动自检 PasswordHashAuditRunner 统一强制重置（防历史明文残留可登录凭据）
                log.warn("用户[{}]密码非 BCrypt 哈希，拒绝登录并计入失败（SEC-06）", username);
                matched = false;
            }
            if (!matched) {
                throw new RuntimeException("密码错误");
            }
        } catch (RuntimeException e) {
            // 失败计数留痕（日志+审计），达到阈值触发锁定
            loginAttemptService.recordFailure(username);
            throw e;
        }
        // 登录成功，清空失败计数
        loginAttemptService.clear(username);

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("token", jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(),
                Boolean.TRUE.equals(user.getMustChangePassword())));
        // AUD-03 密码定期提醒：返回密码是否临近过期（剩余有效期不足 15 天），前端据此提示用户改密
        result.put("passwordExpiringSoon", user.isPasswordExpiringSoon());
        user.setPassword(null);
        result.put("user", user);
        return result;
    }

    /**
     * 用户注册：密码BCrypt加密存储
     */
    public User register(User user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new RuntimeException("用户名不能为空");
        }
        validatePasswordComplexity(user.getPassword());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, user.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("用户名已存在");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        // 安全修复（5.2.3）：公开注册一律创建普通用户，防止客户端传入 role 提权为管理员
        user.setRole("user");
        // FUN-16：与 UserController.create 的 GAP-027 闭环口径对齐——新增用户一律置强制改密标志，
        // 注册设的密码视为初始口令，首次登录必须先改密
        user.setMustChangePassword(true);
        // AUD-03 密码定期提醒：注册即首次设密，记录密码修改时间
        user.setPasswordUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        user.setPassword(null);
        return user;
    }

    /**
     * 修改密码：校验旧密码后更新
     * GAP-027：强制改密（must_change_password=1）账号跳过旧密码校验；改密成功清除强制改密标志
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        validatePasswordComplexity(newPassword);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // GAP-027：强制改密场景（首次登录/初始口令）无需校验旧密码
        boolean forcedChange = Boolean.TRUE.equals(user.getMustChangePassword());
        if (!forcedChange) {
            // SEC-06：仅接受 BCrypt 密文校验，非 $2a$ 哈希视为无效（关闭明文比对通道）
            boolean matched = user.getPassword() != null
                    && user.getPassword().startsWith("$2a$")
                    && passwordEncoder.matches(oldPassword, user.getPassword());
            if (!matched) {
                throw new RuntimeException("原密码错误");
            }
        }
        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(false);   // GAP-027：改密成功清除强制改密标志
        // AUD-03 密码定期提醒：改密成功后刷新密码修改时间，重置过期倒计时
        update.setPasswordUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
    }

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    /** 密码复杂度校验（5.2.2 访问安全）：至少8位，且同时包含大写字母、小写字母和数字。
     *  CQ-07：唯一校验实现——register/changePassword/Controller 创建与重置密码均复用本方法；
     *  抛 IllegalArgumentException 由 GlobalExceptionHandler 统一映射为 400 业务消息。 */
    public void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码长度不能少于8位");
        }
        if (!password.matches(".*[a-z].*") || !password.matches(".*[A-Z].*") || !password.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("密码需同时包含大写字母、小写字母和数字");
        }
    }
}
