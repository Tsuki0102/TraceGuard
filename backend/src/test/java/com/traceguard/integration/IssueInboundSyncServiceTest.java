package com.traceguard.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.common.DefectStatus;
import com.traceguard.entity.Defect;
import com.traceguard.mapper.DefectMapper;
import com.traceguard.service.ResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-009 入站同步（远程->本地）单元测试
 * 验证：定时轮询/手动触发时，远程状态变化能正确回写本地缺陷状态，且非法流转被跳过。
 */
@DisplayName("GAP-009 IssueInboundSyncService 单元测试")
@ExtendWith(MockitoExtension.class)
class IssueInboundSyncServiceTest {

    @Mock
    private JiraClient jiraClient;
    @Mock
    private ZentaoClient zentaoClient;
    @Mock
    private DefectMapper defectMapper;
    @Mock
    private ResultService resultService;
    @Mock
    private IntegrationProperties properties;

    private IssueInboundSyncService service;

    @BeforeEach
    void setUp() {
        IntegrationProperties.InboundSyncConfig cfg = new IntegrationProperties.InboundSyncConfig();
        cfg.setEnabled(true);
        cfg.setBatchSize(200);
        org.mockito.Mockito.lenient().when(properties.getInboundSync()).thenReturn(cfg);

        service = new IssueInboundSyncService();
        // 反射注入 mock
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jiraClient", jiraClient);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "zentaoClient", zentaoClient);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "defectMapper", defectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resultService", resultService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "properties", properties);
    }

    @Test
    @DisplayName("Jira 远程状态变为 Done -> 本地 resolved 回写")
    void jiraStatusChangeTriggersLocalUpdate() {
        Defect defect = new Defect();
        defect.setId(10L);
        defect.setRemoteIssueKey("ISSUE-101");
        defect.setStatus(DefectStatus.PROCESSING.getCode());
        when(jiraClient.enabled()).thenReturn(true);
        when(defectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(defect));
        when(jiraClient.fetchRemoteStatus("ISSUE-101")).thenReturn(
                RemoteIssueStatus.builder().rawStatus("Done").status("resolved").build());

        Map<String, Object> stats = service.doSync();

        assertThat(stats.get("changed")).isEqualTo(1);
        verify(resultService).updateDefectStatus(eq(10L), eq("resolved"), eq(0L));
    }

    @Test
    @DisplayName("远程状态与本地一致 -> 不回写")
    void noChangeWhenSame() {
        Defect defect = new Defect();
        defect.setId(11L);
        defect.setRemoteIssueKey("ISSUE-102");
        defect.setStatus(DefectStatus.PENDING.getCode());
        when(jiraClient.enabled()).thenReturn(true);
        when(defectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(defect));
        when(jiraClient.fetchRemoteStatus("ISSUE-102")).thenReturn(
                RemoteIssueStatus.builder().rawStatus("To Do").status("pending").build());

        Map<String, Object> stats = service.doSync();

        assertThat(stats.get("changed")).isEqualTo(0);
        verify(resultService, never()).updateDefectStatus(anyLong(), any(), any());
    }

    @Test
    @DisplayName("禅道 bug 状态变为 resolved -> 本地 resolved 回写")
    void zentaoStatusChangeTriggersLocalUpdate() {
        Defect defect = new Defect();
        defect.setId(12L);
        defect.setRemoteIssueKey("bug-201");
        defect.setStatus(DefectStatus.PROCESSING.getCode());
        when(zentaoClient.enabled()).thenReturn(true);
        when(defectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(defect));
        when(zentaoClient.fetchRemoteStatus("bug-201")).thenReturn(
                RemoteIssueStatus.builder().rawStatus("resolved").status("resolved").build());

        Map<String, Object> stats = service.doSync();

        assertThat(stats.get("changed")).isEqualTo(1);
        verify(resultService).updateDefectStatus(eq(12L), eq("resolved"), eq(0L));
    }

    @Test
    @DisplayName("客户端未启用 -> 手动触发抛异常")
    void manualTriggerThrowsWhenDisabled() {
        when(jiraClient.enabled()).thenReturn(false);
        when(zentaoClient.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service.triggerManualSync())
                .isInstanceOf(com.traceguard.common.BusinessException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("配置 enabled=false -> 定时任务直接跳过不查询")
    void scheduledSkipsWhenDisabled() {
        IntegrationProperties.InboundSyncConfig cfg = new IntegrationProperties.InboundSyncConfig();
        cfg.setEnabled(false);
        when(properties.getInboundSync()).thenReturn(cfg);

        service.scheduledSync();
        verify(defectMapper, never()).selectList(any());
    }
}
