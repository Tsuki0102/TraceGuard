package com.traceguard.integration;

import com.traceguard.common.BusinessException;
import com.traceguard.common.Result;
import com.traceguard.controller.IntegrationController;
import com.traceguard.entity.Defect;
import com.traceguard.entity.Requirement;
import com.traceguard.service.ProjectService;
import com.traceguard.service.ResultService;
import com.traceguard.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-009：IntegrationController mock 单测（设计 3.7.11）
 * 覆盖：连通测试、需求导出为 issue、缺陷推送、幂等重推走 update、enabled=false 行为。
 */
@DisplayName("GAP-009 IntegrationController 单元测试")
@ExtendWith(MockitoExtension.class)
class IntegrationControllerTest {

    @Mock
    private ResultService resultService;
    @Mock
    private ProjectService projectService;
    @Mock
    private JiraClient jiraClient;
    @Mock
    private ZentaoClient zentaoClient;
    @Mock
    private WeComClient wecomClient;
    @Mock
    private DingTalkClient dingtalkClient;

    private IntegrationController controller;

    @BeforeEach
    void setUp() {
        // 各客户端为条件装配的可选注入字段，用反射注入 mock
        controller = new IntegrationController(resultService, new IntegrationProperties(), projectService);
        ReflectionTestUtils.setField(controller, "jiraClient", jiraClient);
        ReflectionTestUtils.setField(controller, "zentaoClient", zentaoClient);
        ReflectionTestUtils.setField(controller, "wecomClient", wecomClient);
        ReflectionTestUtils.setField(controller, "dingtalkClient", dingtalkClient);
        // SEC-04：默认以管理员身份调用（连通测试/通知接口要求管理员权限）
        UserContext.set(1L, "tester", "admin");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("连通测试：返回可达与版本信息")
    void testConnection() {
        when(jiraClient.testConnection()).thenReturn(ConnectivityResult.builder()
                .toolType(ToolType.JIRA).reachable(true).message("200 OK").version("Test User").build());

        ResponseEntity<Result<ConnectivityResult>> resp = controller.testConnection(ToolType.JIRA);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData().isReachable()).isTrue();
        assertThat(resp.getBody().getData().getVersion()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("需求导出为 issue：新建并回写 remote_issue_key")
    void exportRequirementsCreate() {
        when(jiraClient.enabled()).thenReturn(true);
        Requirement req = new Requirement();
        req.setId(1L);
        req.setRequirementId("REQ-001");
        req.setOriginalText("系统应支持用户登录");
        when(resultService.getRequirementById(1L)).thenReturn(req);
        when(jiraClient.createIssue(any())).thenReturn(IssueRef.builder()
                .remoteKey("ISSUE-101").remoteUrl("https://jira/browse/ISSUE-101").created(true).build());

        ResponseEntity<List<IssueRef>> resp = controller.exportRequirements(1L, List.of(1L), ToolType.JIRA);
        List<IssueRef> refs = resp.getBody();
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getRemoteKey()).isEqualTo("ISSUE-101");
        verify(resultService).updateRequirementRemoteKey(req);
        assertThat(req.getRemoteIssueKey()).isEqualTo("ISSUE-101");
    }

    @Test
    @DisplayName("需求导出：已推送（remote_issue_key 非空）幂等重推走 update")
    void exportRequirementsIdempotentUpdate() {
        when(jiraClient.enabled()).thenReturn(true);
        Requirement req = new Requirement();
        req.setId(1L);
        req.setRemoteIssueKey("ISSUE-101");
        when(resultService.getRequirementById(1L)).thenReturn(req);
        when(jiraClient.updateIssue(eq("ISSUE-101"), any())).thenReturn(IssueRef.builder()
                .remoteKey("ISSUE-101").created(false).build());

        ResponseEntity<List<IssueRef>> resp = controller.exportRequirements(1L, List.of(1L), ToolType.JIRA);
        List<IssueRef> refs = resp.getBody();
        assertThat(refs).hasSize(1);
        verify(jiraClient, never()).createIssue(any());
        verify(jiraClient).updateIssue(eq("ISSUE-101"), any());
    }

    @Test
    @DisplayName("缺陷推送：新建 BUG 并回写 remote_issue_key")
    void pushDefectsCreate() {
        when(jiraClient.enabled()).thenReturn(true);
        Defect defect = new Defect();
        defect.setId(2L);
        defect.setDefectId("D-001");
        defect.setDefectType("业务逻辑不一致");
        defect.setDefectLevel("serious");
        when(resultService.getDefectById(2L)).thenReturn(defect);
        when(jiraClient.createIssue(any())).thenReturn(IssueRef.builder()
                .remoteKey("BUG-201").created(true).build());

        ResponseEntity<List<IssueRef>> resp = controller.pushDefects(1L, List.of(2L), ToolType.JIRA);
        List<IssueRef> refs = resp.getBody();
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getRemoteKey()).isEqualTo("BUG-201");
        verify(resultService).updateDefectRemoteKey(defect);
        assertThat(defect.getRemoteIssueKey()).isEqualTo("BUG-201");
    }

    @Test
    @DisplayName("enabled=false：导出接口返回未启用提示")
    void exportDisabled() {
        when(jiraClient.enabled()).thenReturn(false);
        assertThatThrownBy(() -> controller.exportRequirements(1L, List.of(1L), ToolType.JIRA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("需求变更联动：已导出需求同步最新内容到远程 issue")
    void syncRequirementOk() {
        when(jiraClient.enabled()).thenReturn(true);
        Requirement req = new Requirement();
        req.setId(1L);
        req.setRequirementId("REQ-001");
        req.setOriginalText("系统应支持用户登录（变更后）");
        req.setRemoteIssueKey("ISSUE-101");
        when(resultService.getRequirementById(1L)).thenReturn(req);
        when(jiraClient.updateIssue(eq("ISSUE-101"), any())).thenReturn(IssueRef.builder()
                .remoteKey("ISSUE-101").remoteUrl("https://jira/browse/ISSUE-101").created(false).build());

        ResponseEntity<Result<IssueRef>> resp = controller.syncRequirement(1L, 1L, ToolType.JIRA);

        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData().getRemoteKey()).isEqualTo("ISSUE-101");
        verify(jiraClient).updateIssue(eq("ISSUE-101"), any());
    }

    @Test
    @DisplayName("需求变更联动：未导出的需求返回明确提示")
    void syncRequirementNotExported() {
        when(jiraClient.enabled()).thenReturn(true);
        Requirement req = new Requirement();
        req.setId(1L);
        req.setRequirementId("REQ-001");
        req.setOriginalText("未导出需求");
        when(resultService.getRequirementById(1L)).thenReturn(req);

        assertThatThrownBy(() -> controller.syncRequirement(1L, 1L, ToolType.JIRA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未导出");
        verify(jiraClient, never()).updateIssue(any(), any());
    }

    @Test
    @DisplayName("需求变更联动：需求不存在返回 404")
    void syncRequirementNotFound() {
        when(jiraClient.enabled()).thenReturn(true);
        when(resultService.getRequirementById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.syncRequirement(1L, 999L, ToolType.JIRA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("需求不存在");
    }

    @Test
    @DisplayName("Webhook 通知：企微发送成功返回 true")
    void notifyWeCom() {
        when(wecomClient.enabled()).thenReturn(true);
        when(wecomClient.sendMessage(any())).thenReturn(true);

        ResponseEntity<Result<Boolean>> resp = controller.notify(ToolType.WECOM,
                NotificationMessage.builder().msgType("markdown").title("测试").content("内容").build());

        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData()).isTrue();
        verify(wecomClient).sendMessage(any());
    }

    @Test
    @DisplayName("Webhook 通知：钉钉发送失败返回 502")
    void notifyDingTalkFail() {
        when(dingtalkClient.enabled()).thenReturn(true);
        when(dingtalkClient.sendMessage(any())).thenReturn(false);

        assertThatThrownBy(() -> controller.notify(ToolType.DINGTALK,
                NotificationMessage.builder().content("内容").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("通知发送失败");
    }

    @Test
    @DisplayName("Webhook 通知：非 Webhook 工具返回不支持")
    void notifyUnsupportedTool() {
        assertThatThrownBy(() -> controller.notify(ToolType.JIRA, NotificationMessage.builder().build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持 Webhook 通知");
    }

    @Test
    @DisplayName("数据隔离：非项目归属用户导出需求被拒绝（第 10 项）")
    void exportRequirementsOwnershipBlocked() {
        doThrow(new BusinessException(403, "无权限：仅项目创建者可访问该项目数据"))
                .when(projectService).checkOwnership(1L);

        assertThatThrownBy(() -> controller.exportRequirements(1L, List.of(1L), ToolType.JIRA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
        verify(resultService, never()).getRequirementById(any());
    }

    @Test
    @DisplayName("数据隔离：非项目归属用户推送缺陷被拒绝（第 10 项）")
    void pushDefectsOwnershipBlocked() {
        doThrow(new BusinessException(403, "无权限：仅项目创建者可访问该项目数据"))
                .when(projectService).checkOwnership(1L);

        assertThatThrownBy(() -> controller.pushDefects(1L, List.of(2L), ToolType.JIRA))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
        verify(resultService, never()).getDefectById(any());
    }

    @Test
    @DisplayName("SEC-04：非管理员调用连通测试返回 403")
    void nonAdminTestConnectionForbidden() {
        UserContext.set(1L, "tester", "user");
        ResponseEntity<Result<ConnectivityResult>> resp = controller.testConnection(ToolType.JIRA);
        assertThat(resp.getBody().getCode()).isEqualTo(403);
        verify(jiraClient, never()).testConnection();
    }

    @Test
    @DisplayName("SEC-04：非管理员调用通知发送返回 403")
    void nonAdminNotifyForbidden() {
        UserContext.set(1L, "tester", "user");
        ResponseEntity<Result<Boolean>> resp = controller.notify(ToolType.WECOM,
                NotificationMessage.builder().content("内容").build());
        assertThat(resp.getBody().getCode()).isEqualTo(403);
        verify(wecomClient, never()).sendMessage(any());
    }
}
