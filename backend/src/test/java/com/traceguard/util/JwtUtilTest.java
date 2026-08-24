package com.traceguard.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtil 单元测试：Token生成、解析、校验、防篡改
 */
@DisplayName("JwtUtil单元测试")
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "TraceGuard-JWT-Secret-Key-2026-For-Requirement-Code-Consistency-System-Must-Be-Long");
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 72L);
        jwtUtil.init();
    }

    @Test
    @DisplayName("生成Token并成功解析出用户信息")
    void generateAndParseToken() {
        String token = jwtUtil.generateToken(1L, "admin", "admin", false);
        assertThat(token).isNotBlank();

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(jwtUtil.getUserId(token)).isEqualTo(1L);
        assertThat(jwtUtil.getUsername(token)).isEqualTo("admin");
        assertThat(jwtUtil.getRole(token)).isEqualTo("admin");
    }

    @Test
    @DisplayName("GAP-027：mustChangePassword 标志写入JWT载荷并可解析")
    void mustChangePasswordClaim() {
        String token = jwtUtil.generateToken(1L, "admin", "admin", true);
        assertThat(jwtUtil.getMustChangePassword(token)).isTrue();

        String normalToken = jwtUtil.generateToken(2L, "tester", "user", false);
        assertThat(jwtUtil.getMustChangePassword(normalToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken对合法Token返回true")
    void validateValidToken() {
        String token = jwtUtil.generateToken(2L, "tester", "user", false);
        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("篡改Token后校验失败返回null")
    void parseTamperedTokenReturnsNull() {
        String token = jwtUtil.generateToken(1L, "admin", "admin", false);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThat(jwtUtil.parseToken(tampered)).isNull();
        assertThat(jwtUtil.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("SEC-02：弱/缺失 JWT 密钥启动即拒绝（fail-fast）")
    void weakSecretRejectedOnInit() {
        JwtUtil weak = new JwtUtil();
        ReflectionTestUtils.setField(weak, "secret", "short-key");
        assertThatThrownBy(weak::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        JwtUtil empty = new JwtUtil();
        ReflectionTestUtils.setField(empty, "secret", "  ");
        assertThatThrownBy(empty::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("过期Token解析返回null")
    void parseExpiredTokenReturnsNull() {
        ReflectionTestUtils.setField(jwtUtil, "expireHours", -1L);
        jwtUtil.init();
        String token = jwtUtil.generateToken(1L, "admin", "admin", false);
        assertThat(jwtUtil.parseToken(token)).isNull();
    }

    @Test
    @DisplayName("非法格式Token与null输入均返回null且不抛异常")
    void parseInvalidTokenReturnsNull() {
        assertThat(jwtUtil.parseToken("not-a-jwt-token")).isNull();
        assertThat(jwtUtil.parseToken(null)).isNull();
        assertThat(jwtUtil.parseToken("")).isNull();
    }

    @Test
    @DisplayName("无效Token的getUserId返回null")
    void getUserIdFromInvalidToken() {
        assertThat(jwtUtil.getUserId("invalid-token")).isNull();
        assertThat(jwtUtil.getUsername("invalid-token")).isNull();
        assertThat(jwtUtil.getRole("invalid-token")).isNull();
    }
}
