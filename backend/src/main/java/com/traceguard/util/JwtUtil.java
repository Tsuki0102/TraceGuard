package com.traceguard.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT Token 工具类
 * 负责 Token 的生成、解析与校验
 */
@Component
public class JwtUtil {

    @Value("${traceguard.jwt.secret}")
    private String secret;

    @Value("${traceguard.jwt.expire-hours:72}")
    private long expireHours;

    private Key signingKey;

    /** HS256 要求密钥至少 32 字节（256 bit），低于该值或缺失直接拒绝启动（SEC-02 fail-fast） */
    private static final int MIN_SECRET_LENGTH = 32;

    @PostConstruct
    public void init() {
        if (secret == null || secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT 密钥未配置或强度不足（至少 " + MIN_SECRET_LENGTH +
                    " 字符），请通过环境变量 JWT_SECRET 注入强随机密钥后重启（SEC-02 fail-fast）");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成JWT Token
     * @param mustChangePassword GAP-027：登录后是否需强制改密（JWT 载荷携带，供守卫二次校验）
     */
    public String generateToken(Long userId, String username, String role, boolean mustChangePassword) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        claims.put("mustChangePassword", mustChangePassword);
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireHours * 3600 * 1000);
        return Jwts.builder()
                .setClaims(claims)
                // SEC-16：jti 唯一标识，供登出时加入黑名单吊销
                .setId(UUID.randomUUID().toString())
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /** SEC-16：从 Token 中获取 jti（登出吊销用） */
    public String getJti(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getId() : null;
    }

    /**
     * 解析Token，失败返回null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验Token是否有效
     */
    public boolean validateToken(String token) {
        return parseToken(token) != null;
    }

    /**
     * 从Token中获取用户ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return null;
        Object userId = claims.get("userId");
        if (userId instanceof Number) {
            return ((Number) userId).longValue();
        }
        return null;
    }

    /**
     * 从Token中获取用户名
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * 从Token中获取角色
     */
    public String getRole(String token) {
        Claims claims = parseToken(token);
        return claims != null ? (String) claims.get("role") : null;
    }

    /**
     * 从Token中获取强制改密标志（GAP-027，守卫二次校验用）
     */
    public Boolean getMustChangePassword(String token) {
        Claims claims = parseToken(token);
        return claims != null ? (Boolean) claims.get("mustChangePassword") : null;
    }

    /** 获取 token 有效期（小时），供 HttpOnly Cookie maxAge 使用（SEC-10①） */
    public long getExpireHours() {
        return expireHours;
    }
}
