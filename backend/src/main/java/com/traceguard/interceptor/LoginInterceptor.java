package com.traceguard.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.common.Result;
import com.traceguard.entity.User;
import com.traceguard.mapper.UserMapper;
import com.traceguard.service.TokenBlacklistService;
import com.traceguard.util.JwtUtil;
import com.traceguard.util.UserContext;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 登录鉴权拦截器
 * 校验请求头中的JWT Token，并将用户信息放入ThreadLocal上下文
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // SEC-10①：优先 Authorization 头（兼容），其次 HttpOnly Cookie tg_token（主通道，JS 不可读）
        String token = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            javax.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (javax.servlet.http.Cookie c : cookies) {
                    if ("tg_token".equals(c.getName())) {
                        token = c.getValue();
                        break;
                    }
                }
            }
        }
        if (token == null) {
            writeUnauthorized(response, "未登录或Token缺失，请重新登录");
            return false;
        }
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeUnauthorized(response, "Token无效或已过期，请重新登录");
            return false;
        }

        // SEC-16：校验 jti 是否已被登出吊销（黑名单命中即拒绝，使登出立即生效）
        if (claims.getId() != null && tokenBlacklistService.isRevoked(claims.getId())) {
            writeUnauthorized(response, "Token已失效，请重新登录");
            return false;
        }

        Object userIdObj = claims.get("userId");
        Long userId = userIdObj instanceof Number ? ((Number) userIdObj).longValue() : null;
        String username = claims.getSubject();

        // 安全修复（5.2.3）：权限以数据库实时角色为准，防止角色变更/删除用户后旧Token在有效期内继续保有admin权限
        User dbUser = userId != null ? userMapper.selectById(userId) : null;
        if (dbUser == null) {
            writeUnauthorized(response, "用户不存在或已被删除，请重新登录");
            return false;
        }

        // GAP-027：强制改密账号仅放行改密与用户信息接口，其余业务接口一律 403
        if (Boolean.TRUE.equals(dbUser.getMustChangePassword())) {
            String path = request.getRequestURI();
            if (!path.startsWith("/api/auth/change-password") && !path.startsWith("/api/auth/info")
                    && !path.startsWith("/auth/change-password") && !path.startsWith("/auth/info")) {
                writeForbidden(response, "当前账号使用初始口令，必须修改密码后才能继续使用");
                return false;
            }
        }

        UserContext.set(dbUser.getId(), username != null ? username : dbUser.getUsername(), dbUser.getRole());
        log.debug("用户[{}]访问接口: {}", username, request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.error(401, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    /** GAP-027：强制改密账号访问业务接口返回 403 */
    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.error(403, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
