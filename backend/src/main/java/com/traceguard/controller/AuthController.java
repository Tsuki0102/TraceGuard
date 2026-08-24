package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.dto.ChangePasswordDTO;
import com.traceguard.dto.LoginDTO;
import com.traceguard.dto.RegisterDTO;
import com.traceguard.entity.User;
import com.traceguard.service.TokenBlacklistService;
import com.traceguard.service.UserService;
import com.traceguard.util.JwtUtil;
import com.traceguard.util.UserContext;
import io.jsonwebtoken.Claims;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@Api(tags = "01-认证登录")
public class AuthController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @ApiOperation(value = "用户登录", notes = "用户名密码登录；本接口不经过登录拦截器，通过请求属性向审计拦截器传递操作者信息；SEC-10①：JWT 改经 HttpOnly Cookie 下发，响应体不再返回 token")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        String username = dto.getUsername();
        try {
            String password = dto.getPassword();
            Map<String, Object> result = userService.login(username, password);
            // SEC-10①：从响应体摘除 token（JS 不可见），改由 HttpOnly Cookie 承载（XSS 无法窃取）
            String token = (String) result.remove("token");
            addAuthCookies(response, token, jwtUtil.getExpireHours());
            // 登录接口不经过LoginInterceptor，通过请求属性向审计拦截器传递操作者信息
            request.setAttribute("auditUsername", username);
            return Result.success(result);
        } catch (Exception e) {
            // SEC-14：审计记录固定失败消息（不回显内部异常详情），异常上抛由全局异常处理器统一兜底
            request.setAttribute("auditUsername", username);
            request.setAttribute("auditError", "登录失败");
            throw e;
        }
    }

    /** SEC-10①：签发认证 Cookie——tg_token(HttpOnly) 承载 JWT，tg_logged_in 供前端路由守卫判断登录态 */
    private void addAuthCookies(HttpServletResponse response, String token, long expireHours) {
        // 使用 Spring ResponseCookie：可靠携带 HttpOnly/SameSite=Lat/Max-Age，规避 Servlet Cookie API 差异
        org.springframework.http.ResponseCookie tokenCookie = org.springframework.http.ResponseCookie
                .from("tg_token", token)
                .httpOnly(true)
                .path("/")
                .maxAge(expireHours * 3600)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", tokenCookie.toString());

        org.springframework.http.ResponseCookie flagCookie = org.springframework.http.ResponseCookie
                .from("tg_logged_in", "1")
                .path("/")
                .maxAge(expireHours * 3600)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", flagCookie.toString());
    }

    /** 清除认证 Cookie（登出调用） */
    private void clearAuthCookies(HttpServletResponse response) {
        org.springframework.http.ResponseCookie tokenCookie = org.springframework.http.ResponseCookie
                .from("tg_token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", tokenCookie.toString());

        org.springframework.http.ResponseCookie flagCookie = org.springframework.http.ResponseCookie
                .from("tg_logged_in", "")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", flagCookie.toString());
    }

    @ApiOperation(value = "用户注册", notes = "注册新用户；通过请求属性向审计拦截器记录操作者信息")
    @PostMapping("/register")
    public Result<User> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        String username = dto.getUsername();
        try {
            // CQ-03：DTO 参数校验（@Valid），转换为 User 后走 UserService 单点注册（含密码复杂度二次校验）
            User user = new User();
            user.setUsername(dto.getUsername());
            user.setPassword(dto.getPassword());
            user.setRealName(dto.getRealName());
            user.setEmail(dto.getEmail());
            User registered = userService.register(user);
            request.setAttribute("auditUsername", username);
            return Result.success(registered);
        } catch (Exception e) {
            // SEC-14：审计记录固定失败消息，异常上抛由全局异常处理器统一兜底
            request.setAttribute("auditUsername", username);
            request.setAttribute("auditError", "注册失败");
            throw e;
        }
    }

    @ApiOperation(value = "退出登录", notes = "SEC-16：将当前 JWT 的 jti 加入黑名单使其立即失效，并清除 HttpOnly 认证 Cookie（SEC-10①）；需登录")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        // SEC-16：登出吊销——解析 token 的 jti 与过期时间，加入黑名单后该 token 立即失效
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (request.getCookies() != null) {
            for (javax.servlet.http.Cookie c : request.getCookies()) {
                if ("tg_token".equals(c.getName())) {
                    token = c.getValue();
                    break;
                }
            }
        }
        if (token != null) {
            Claims claims = jwtUtil.parseToken(token);
            if (claims != null && claims.getId() != null) {
                long expireAt = claims.getExpiration() != null
                        ? claims.getExpiration().getTime()
                        : System.currentTimeMillis() + 3600_000L;
                tokenBlacklistService.revoke(claims.getId(), expireAt);
            }
        }
        // SEC-10①：清除 HttpOnly 认证 Cookie
        clearAuthCookies(response);
        return Result.success();
    }

    @ApiOperation(value = "按用户ID查询用户信息", notes = "仅可查询本人信息，管理员可查询任意用户（防越权枚举用户资料）")
    @GetMapping("/info/{id}")
    public Result<User> getUserInfo(@PathVariable Long id) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            return Result.error(401, "未登录");
        }
        if (!currentUserId.equals(id) && !UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅可查询本人信息");
        }
        return Result.success(userService.getById(id));
    }

    @ApiOperation(value = "获取当前登录用户信息", notes = "从会话上下文解析当前用户 ID，未登录返回 401")
    @GetMapping("/info")
    public Result<User> getCurrentUserInfo() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        return Result.success(userService.getById(userId));
    }

    @ApiOperation(value = "修改当前用户密码", notes = "需登录；校验旧密码，新密码须至少8位且同时包含大写字母、小写字母和数字（与5.2.2访问安全规则统一）")
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        // CQ-03：参数校验由 @Valid 注解驱动（GlobalExceptionHandler 映射 400），业务密码校验仍走 UserService 单点
        userService.changePassword(userId, dto.getOldPassword(), dto.getNewPassword());
        return Result.success();
    }
}
