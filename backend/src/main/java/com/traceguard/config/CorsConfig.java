package com.traceguard.config;

import com.traceguard.interceptor.AuditInterceptor;
import com.traceguard.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private AuditInterceptor auditInterceptor;

    /** SEC-05：允许跨域来源白名单（逗号分隔）。dev 默认本地 3000；prod 经 CORS_ALLOWED_ORIGINS 注入部署域名 */
    @Value("${traceguard.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // SEC-05：收敛为显式白名单，不再放行任意来源携带凭证（allowedOriginPatterns("*") + credentials 组合存在 CSRF/数据泄露风险）
        // 白名单为空（prod 未注入 CORS_ALLOWED_ORIGINS）时拒绝一切跨域，仅同源访问（fail-closed）
        String[] origins = allowedOrigins == null || allowedOrigins.trim().isEmpty()
                ? new String[0] : allowedOrigins.split(",");
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 登录注册接口放行
                        "/auth/login",
                        "/auth/register",
                        // API文档放行
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v2/api-docs",
                        "/v2/api-docs/**",
                        "/v3/api-docs/**",
                        // 静态资源放行
                        "/favicon.ico",
                        "/error",
                        // WebSocket握手放行（连接鉴权在Handler内通过一次性ticket完成）；
                        // 注意：/ws/ticket 换取票据的接口仍需登录拦截，故仅排除握手端点
                        "/ws/progress"
                );
        // 审计拦截器：注册在LoginInterceptor之后，afterCompletion逆序执行时UserContext仍可用
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns("/**");
    }
}
