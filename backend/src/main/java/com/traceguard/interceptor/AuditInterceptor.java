package com.traceguard.interceptor;

import com.traceguard.service.AuditService;
import com.traceguard.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作审计拦截器：记录所有写操作（POST/PUT/DELETE）的审计日志
 * 注意：需注册在LoginInterceptor之后，afterCompletion逆序执行时UserContext仍可用
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditInterceptor.class);

    private static final String START_TIME_ATTR = "auditStartTime";

    @Autowired
    private AuditService auditService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return;
        }
        try {
            String path = request.getRequestURI();
            Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
            long costMs = startTime != null ? System.currentTimeMillis() - startTime : 0;
            String operation = resolveOperation(method, path);
            String params = resolveParams(request);
            String ip = resolveIp(request);
            // 业务异常多由GlobalExceptionHandler处理（此时afterCompletion的ex为null），
            // 失败请求须以HTTP状态码>=400兜底判定，否则审计日志会漏记失败
            String errorMsg = ex != null ? (ex.getMessage() != null ? ex.getMessage() : ex.toString()) : null;
            if (errorMsg == null && response.getStatus() >= 400) {
                errorMsg = "HTTP " + response.getStatus();
            }
            String username = UserContext.getUsername();
            Long userId = UserContext.getUserId();
            // 登录/注册接口未经过LoginInterceptor，从Controller写入的请求属性中补齐操作者信息
            if (username == null) {
                Object attrName = request.getAttribute("auditUsername");
                if (attrName != null) username = String.valueOf(attrName);
                Object attrErr = request.getAttribute("auditError");
                if (attrErr != null) errorMsg = String.valueOf(attrErr);
            }
            auditService.record(userId, username,
                    operation, method, path, params, ip,
                    response.getStatus(), costMs, errorMsg);
        } catch (Exception e) {
            LOGGER.warn("审计拦截器异常: {}", e.getMessage());
        }
    }

    /** 根据HTTP方法与路径推断操作类型 */
    private String resolveOperation(String method, String path) {
        if (path.contains("/auth/login")) return "登录";
        if (path.contains("/auth/register")) return "注册";
        if (path.contains("/auth/change-password")) return "修改密码";
        if (path.contains("/user/reset-password")) return "重置密码";
        if (path.contains("/user/create")) return "创建用户";
        if (path.contains("/user/update")) return "更新用户";
        if (path.contains("/user/")) return "删除用户";
        if (path.contains("/backup/create")) return "创建备份";
        if (path.contains("/backup/restore")) return "恢复备份";
        if (path.contains("/backup/")) return "删除备份";
        if (path.contains("/analysis/upload")) return "上传文件";
        if (path.contains("/analysis/task/run")) return "启动分析";
        if (path.contains("/analysis/task/pause")) return "暂停任务";
        if (path.contains("/analysis/task/resume")) return "恢复任务";
        if (path.contains("/analysis/task/terminate")) return "终止任务";
        if (path.contains("/analysis/task/rerun")) return "重新分析";
        if (path.contains("/spec/update")) return "编辑规约";
        if (path.contains("/audit/export")) return "导出审计日志";
        if (path.contains("/project/create")) return "创建项目";
        if (path.contains("/project/update")) return "更新项目";
        if (path.contains("/project/archive")) return "归档项目";
        if (path.contains("/project/restore")) return "恢复项目";
        if (path.contains("/project/")) return "删除项目";
        if ("POST".equalsIgnoreCase(method)) return "创建";
        if ("PUT".equalsIgnoreCase(method)) return "更新";
        return "删除";
    }

    /** 请求参数摘要：query string + 路径变量（不上报请求体，避免记录密码等敏感信息） */
    private String resolveParams(HttpServletRequest request) {
        Map<String, String[]> map = new LinkedHashMap<>(request.getParameterMap());
        map.remove("password");
        map.remove("newPassword");
        map.remove("oldPassword");
        map.remove("token");
        map.remove("apiKey");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(e.getKey()).append("=").append(String.join(",", e.getValue()));
            if (sb.length() > 900) break;
        }
        return sb.toString();
    }

    private String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}
