package com.traceguard.websocket;

import com.traceguard.entity.AnalysisTask;
import com.traceguard.entity.Project;
import com.traceguard.mapper.AnalysisTaskMapper;
import com.traceguard.mapper.ProjectMapper;
import com.traceguard.mapper.UserMapper;
import com.traceguard.websocket.WSTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进度推送数据隔离测试（SRS 5.2.3）：任务进度仅推送给项目创建者与管理员
 */
class ProgressWebSocketHandlerTest {

    private ProgressWebSocketHandler handler;
    private AnalysisTaskMapper analysisTaskMapper;
    private ProjectMapper projectMapper;

    private WebSocketSession ownerSession;   // 任务归属者
    private WebSocketSession otherSession;   // 无关普通用户
    private WebSocketSession adminSession;   // 管理员

    private static final Long TASK_ID = 100L;
    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Long ADMIN_ID = 1L;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ProgressWebSocketHandler();
        // SEC-10：连接鉴权已由 jwtUtil 改为一次性票据 wsTicketService（测试不涉及连接建立，注入 mock 即可）
        ReflectionTestUtils.setField(handler, "wsTicketService", Mockito.mock(WSTicketService.class));
        ReflectionTestUtils.setField(handler, "userMapper", Mockito.mock(UserMapper.class));
        analysisTaskMapper = Mockito.mock(AnalysisTaskMapper.class);
        projectMapper = Mockito.mock(ProjectMapper.class);
        ReflectionTestUtils.setField(handler, "analysisTaskMapper", analysisTaskMapper);
        ReflectionTestUtils.setField(handler, "projectMapper", projectMapper);

        ownerSession = mockSession("owner", OWNER_ID, "user");
        otherSession = mockSession("other", OTHER_ID, "user");
        adminSession = mockSession("admin", ADMIN_ID, "admin");

        // 任务100 -> 项目200 -> 创建者 OWNER_ID
        AnalysisTask task = new AnalysisTask();
        task.setId(TASK_ID);
        task.setProjectId(200L);
        when(analysisTaskMapper.selectById(TASK_ID)).thenReturn(task);
        Project project = new Project();
        project.setId(200L);
        project.setCreateUserId(OWNER_ID);
        when(projectMapper.selectById(200L)).thenReturn(project);

        // 直接注入三个会话（绕过连接建立，仅测推送过滤）
        java.lang.reflect.Field f = ProgressWebSocketHandler.class.getDeclaredField("sessions");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, WebSocketSession> sessions = (java.util.Map<String, WebSocketSession>) f.get(handler);
        sessions.put("owner", ownerSession);
        sessions.put("other", otherSession);
        sessions.put("admin", adminSession);
    }

    @Test
    @DisplayName("进度仅推送归属者与管理员，无关用户不推送")
    void pushProgressShouldOnlyReachOwnerAndAdmin() throws Exception {
        handler.pushProgress(TASK_ID, 40, "running", "解析需求");

        verify(ownerSession, times(1)).sendMessage(any(TextMessage.class));
        verify(adminSession, times(1)).sendMessage(any(TextMessage.class));
        verify(otherSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("任务不存在时仅管理员可见（fail-closed）")
    void pushProgressShouldFailClosedWhenTaskMissing() throws Exception {
        handler.pushProgress(999L, 10, "running", "初始化");

        verify(adminSession, times(1)).sendMessage(any(TextMessage.class));
        verify(ownerSession, never()).sendMessage(any(TextMessage.class));
        verify(otherSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("同一任务重复推送时归属解析走缓存（仅查库一次）")
    void pushProgressShouldCacheTaskOwnerResolution() throws Exception {
        handler.pushProgress(TASK_ID, 20, "running", "步骤1");
        handler.pushProgress(TASK_ID, 50, "running", "步骤2");

        verify(analysisTaskMapper, times(1)).selectById(TASK_ID);
        verify(projectMapper, times(1)).selectById(200L);
        verify(ownerSession, times(2)).sendMessage(any(TextMessage.class));
    }

    /** 构造带 userId/role 属性的会话 mock */
    private WebSocketSession mockSession(String id, Long userId, String role) {
        WebSocketSession session = Mockito.mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        java.util.Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put("role", role);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }
}
