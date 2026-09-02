package com.traceguard.websocket;

import com.traceguard.entity.AnalysisTask;
import com.traceguard.entity.Project;
import com.traceguard.entity.User;
import com.traceguard.mapper.AnalysisTaskMapper;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析任务进度WebSocket推送处理器
 * 连接鉴权（SEC-10）：不再在 URL 传 JWT，改为携带一次性 ticket（GET /ws/ticket 换取，30s 单次有效），
 * 建立连接后回查数据库实时角色/存在性。
 * 推送格式：{"taskId":1,"progress":35,"status":"running","currentStep":"...","timestamp":...}
 * 数据隔离（5.2.3）：进度仅推送至任务所属项目的创建者及管理员会话，不再全员广播
 */
@Component
public class ProgressWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressWebSocketHandler.class);

    @Autowired
    private WSTicketService wsTicketService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AnalysisTaskMapper analysisTaskMapper;

    @Autowired
    private ProjectMapper projectMapper;

    /** 已鉴权的会话：sessionId -> session；指向静态共享表，供 W2-07 系统状态统计在线数 */
    private final Map<String, WebSocketSession> sessions = SessionsRef.SESSIONS;

    /** 任务归属缓存：taskId -> 项目创建者 userId（归属关系不可变，避免每次推送查库） */
    private final Map<Long, Long> taskOwnerCache = new ConcurrentHashMap<>();

    /** CQ-10：归属缓存上限——超过则整体清空（可从 DB 重建），防止无界增长内存泄漏 */
    private static final int TASK_OWNER_CACHE_MAX = 10000;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // SEC-10：校验一次性票据（30s 单次有效），票据换取 userId 后回查数据库实时角色/存在性
        String ticket = extractParam(session, "ticket");
        Long userId = wsTicketService.consume(ticket);
        if (userId == null) {
            LOGGER.warn("WebSocket连接票据无效或已过期，拒绝会话 {}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        // 与LoginInterceptor一致：回查数据库，用户被删除/角色变更后无法建立进度连接
        User user = userMapper.selectById(userId);
        if (user == null) {
            LOGGER.warn("WebSocket连接用户不存在或已删除，拒绝会话 {}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("userId", user.getId());
        session.getAttributes().put("role", user.getRole());
        sessions.put(session.getId(), session);
        LOGGER.info("WebSocket会话已建立: {} (user={}, role={})", session.getId(), user.getUsername(), user.getRole());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        LOGGER.info("WebSocket会话已关闭: {} status={}", session.getId(), status);
    }

    /** W2-07：当前已鉴权在线会话数（系统运行状态卡） */
    public static int onlineCount() {
        return SessionsRef.SESSIONS.size();
    }

    /** 静态引用会话表，供 Dashboard 状态接口统计在线数 */
    private static final class SessionsRef {
        static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端心跳（防代理空闲断连），收到即回pong
        try {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        } catch (IOException e) {
            LOGGER.warn("WebSocket心跳响应失败: sessionId={}", session.getId());
        }
    }

    /**
     * 向任务归属者（项目创建者）及管理员会话推送任务进度（数据隔离，5.2.3）
     */
    public void pushProgress(Long taskId, Integer progress, String status, String currentStep) {
        if (sessions.isEmpty()) return;
        Long ownerUserId = resolveTaskOwner(taskId);
        String payload = String.format(
                "{\"type\":\"progress\",\"taskId\":%d,\"progress\":%d,\"status\":\"%s\",\"currentStep\":\"%s\",\"timestamp\":%d}",
                taskId, progress != null ? progress : 0,
                status != null ? status : "", currentStep != null ? currentStep : "",
                System.currentTimeMillis());
        TextMessage message = new TextMessage(payload);
        sessions.values().forEach(s -> {
            if (!canReceive(s, ownerUserId)) return;
            try {
                synchronized (s) {
                    if (s.isOpen()) s.sendMessage(message);
                }
            } catch (IOException e) {
                LOGGER.warn("进度推送失败，移除会话 {}: {}", s.getId(), e.getMessage());
                sessions.remove(s.getId());
            }
        });
    }

    /** 会话可见性判定：任务归属者本人或管理员 */
    private boolean canReceive(WebSocketSession session, Long ownerUserId) {
        if (ownerUserId == null) {
            // 任务或项目已不存在：仅管理员可见，普通用户一律不推（fail-closed）
            return "admin".equals(session.getAttributes().get("role"));
        }
        if ("admin".equals(session.getAttributes().get("role"))) return true;
        return ownerUserId.equals(session.getAttributes().get("userId"));
    }

    /** 解析任务归属：taskId -> 任务 -> 项目 -> 创建者userId，带缓存 */
    private Long resolveTaskOwner(Long taskId) {
        if (taskId == null) return null;
        Long cached = taskOwnerCache.get(taskId);
        if (cached != null) return cached;
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null || task.getProjectId() == null) return null;
        Project project = projectMapper.selectById(task.getProjectId());
        if (project == null || project.getCreateUserId() == null) return null;
        // CQ-10：缓存达到上限整体清空（归属关系可从 DB 重建），避免无界增长
        if (taskOwnerCache.size() >= TASK_OWNER_CACHE_MAX) {
            taskOwnerCache.clear();
        }
        taskOwnerCache.put(taskId, project.getCreateUserId());
        return project.getCreateUserId();
    }

    /** 从连接URL提取指定 query 参数（SEC-10：仅用于一次性 ticket） */
    private String extractParam(WebSocketSession session, String key) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String param : uri.getQuery().split("&")) {
            if (param.startsWith(key + "=")) {
                return param.substring(key.length() + 1);
            }
        }
        return null;
    }
}
