package com.traceguard.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.entity.User;
import com.traceguard.mapper.UserMapper;
import com.traceguard.service.TokenBlacklistService;
import com.traceguard.util.JwtUtil;
import com.traceguard.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录鉴权拦截器单测（4.10 缺口补齐）：覆盖 5.2.3 实时角色校验、GAP-027 强制改密拦截等安全逻辑。
 * 纯 Mockito（仅 mock UserMapper/Request/Response），JWT 用真实 JwtUtil 生成与解析，避免 mock Claims 的嵌套 stubbing 问题。
 */
class LoginInterceptorTest {

    private LoginInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private JwtUtil jwtUtil;
    private UserMapper userMapper;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new LoginInterceptor();
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        // response.getWriter() 需返回 mock PrintWriter，否则 writeUnauthorized/writeForbidden NPE
        Mockito.when(response.getWriter()).thenReturn(Mockito.mock(PrintWriter.class));
        userMapper = Mockito.mock(UserMapper.class);
        jwtUtil = new JwtUtil();
        // 设置测试密钥并初始化签名键
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-for-unit-test-1234567890");
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 72L);
        jwtUtil.init();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(interceptor, "userMapper", userMapper);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
        // SEC-16：登出吊销黑名单服务（默认未命中，保证既有鉴权用例语义不变）
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", Mockito.mock(TokenBlacklistService.class));
        Mockito.when(request.getMethod()).thenReturn("GET");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private User dbUser(Long id, String username, String role, boolean mustChange) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setMustChangePassword(mustChange);
        return u;
    }

    private String token(Long userId, String username, String role, boolean mustChange) {
        return jwtUtil.generateToken(userId, username, role, mustChange);
    }

    @Test
    @DisplayName("OPTIONS 预检请求直接放行")
    void optionsRequestPassedThrough() throws Exception {
        Mockito.when(request.getMethod()).thenReturn("OPTIONS");
        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("无 Bearer Token 返回 401 未授权")
    void missingTokenReturns401() throws Exception {
        Mockito.when(request.getHeader("Authorization")).thenReturn(null);
        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isFalse();
        Mockito.verify(response).setStatus(401);
    }

    @Test
    @DisplayName("无效/过期 Token 返回 401")
    void invalidTokenReturns401() throws Exception {
        // 真实 jwtUtil 解析非法签名 token 会返回 null（无需 stub 真实对象）
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer not-a-valid-token");
        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isFalse();
        Mockito.verify(response).setStatus(401);
    }

    @Test
    @DisplayName("5.2.3：用户已被删除（实时角色校验）返回 401")
    void deletedUserReturns401() throws Exception {
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + token(1L, "ghost", "user", false));
        Mockito.when(userMapper.selectById(1L)).thenReturn(null);
        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isFalse();
        Mockito.verify(response).setStatus(401);
    }

    @Test
    @DisplayName("合法用户放行且 UserContext 正确设置角色")
    void validUserAllowedAndContextSet() throws Exception {
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + token(2L, "alice", "admin", false));
        Mockito.when(userMapper.selectById(2L)).thenReturn(dbUser(2L, "alice", "admin", false));
        Mockito.when(request.getRequestURI()).thenReturn("/api/result/list");

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(2L);
        assertThat(UserContext.getUsername()).isEqualTo("alice");
        assertThat(UserContext.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("GAP-027：强制改密账号访问业务接口返回 403")
    void mustChangePasswordBlockedForBusinessApi() throws Exception {
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + token(3L, "bob", "user", true));
        Mockito.when(userMapper.selectById(3L)).thenReturn(dbUser(3L, "bob", "user", true));
        Mockito.when(request.getRequestURI()).thenReturn("/api/result/list");

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isFalse();
        Mockito.verify(response).setStatus(403);
    }

    @Test
    @DisplayName("GAP-027：强制改密账号访问改密接口放行")
    void mustChangePasswordAllowedForChangePassword() throws Exception {
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + token(3L, "bob", "user", true));
        Mockito.when(userMapper.selectById(3L)).thenReturn(dbUser(3L, "bob", "user", true));
        Mockito.when(request.getRequestURI()).thenReturn("/api/auth/change-password");

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isTrue();
    }
}
