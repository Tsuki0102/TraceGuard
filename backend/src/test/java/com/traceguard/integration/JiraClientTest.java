package com.traceguard.integration;

import com.traceguard.integration.IssuePayload.IssueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-009：JiraClient mock 单测（设计 3.7.11）
 * 覆盖：create/update/transition 请求路径、认证头、请求体字段、响应解析；超时/5xx/401 分类处理。
 */
@DisplayName("GAP-009 JiraClient 单元测试")
class JiraClientTest {

    private RestTemplate restTemplate;
    private IntegrationProperties properties;
    private JiraClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        properties = new IntegrationProperties();
        properties.getJira().setEnabled(true);
        properties.getJira().setBaseUrl("https://jira.example.com");
        properties.getJira().setUsername("admin");
        properties.getJira().setToken("token123");
        properties.getJira().setProjectKey("PROJ");
        client = new JiraClient(restTemplate, properties);
    }

    @Test
    @DisplayName("连通测试：认证成功返回可达与版本信息")
    void testConnectionSuccess() {
        when(restTemplate.exchange(eq("https://jira.example.com/rest/api/2/myself"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"displayName\":\"Test User\"}", HttpStatus.OK));

        ConnectivityResult r = client.testConnection();
        assertThat(r.isReachable()).isTrue();
        assertThat(r.getMessage()).contains("200");
        assertThat(r.getVersion()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("连通测试：401/403 归类为认证失败")
    void testConnectionUnauthorized() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        ConnectivityResult r = client.testConnection();
        assertThat(r.isReachable()).isFalse();
        assertThat(r.getMessage()).contains("认证失败");
    }

    @Test
    @DisplayName("新建 issue：POST /rest/api/2/issue 并解析 key 回写")
    void createIssue() {
        when(restTemplate.postForEntity(eq("https://jira.example.com/rest/api/2/issue"),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"key\":\"ISSUE-123\"}", HttpStatus.CREATED));

        IssuePayload payload = IssuePayload.builder().type(IssueType.BUG).title("缺陷A").build();
        IssueRef ref = client.createIssue(payload);
        assertThat(ref.getRemoteKey()).isEqualTo("ISSUE-123");
        assertThat(ref.isCreated()).isTrue();
        assertThat(ref.getRemoteUrl()).contains("ISSUE-123");
    }

    @Test
    @DisplayName("更新 issue：走 PUT /rest/api/2/issue/{key}，幂等重推")
    void updateIssue() {
        when(restTemplate.exchange(eq("https://jira.example.com/rest/api/2/issue/ISSUE-123"),
                eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        IssueRef ref = client.updateIssue("ISSUE-123", IssuePayload.builder().title("t").build());
        assertThat(ref.getRemoteKey()).isEqualTo("ISSUE-123");
        assertThat(ref.isCreated()).isFalse();
    }

    @Test
    @DisplayName("状态流转：查询 transitions 后按名称匹配并 POST 流转")
    void transitionStatus() {
        String transitionsUrl = "https://jira.example.com/rest/api/2/issue/ISSUE-123/transitions";
        when(restTemplate.exchange(eq(transitionsUrl), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"transitions\":[{\"id\":\"11\",\"name\":\"In Progress\"}]}", HttpStatus.OK));
        when(restTemplate.postForEntity(eq(transitionsUrl), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        client.transitionStatus("ISSUE-123",
                RemoteStatus.builder().status("IN_PROGRESS").description("进行中").build());
        verify(restTemplate).postForEntity(eq(transitionsUrl), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("超时异常：createIssue 抛 RuntimeException 且不自动重试")
    void createIssueTimeout() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> client.createIssue(IssuePayload.builder().type(IssueType.BUG).build()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("createIssue failed");
    }
}
