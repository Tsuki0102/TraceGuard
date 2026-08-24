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
 * GAP-009：ZentaoClient mock 单测（设计 3.7.11）
 */
@DisplayName("GAP-009 ZentaoClient 单元测试")
class ZentaoClientTest {

    private RestTemplate restTemplate;
    private IntegrationProperties properties;
    private ZentaoClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        properties = new IntegrationProperties();
        properties.getZentao().setEnabled(true);
        properties.getZentao().setBaseUrl("https://zentao.example.com");
        properties.getZentao().setToken("token123");
        properties.getZentao().setProductId("P1");
        client = new ZentaoClient(restTemplate, properties);
    }

    @Test
    @DisplayName("连通测试：GET /api.php/v1/user 成功")
    void testConnectionSuccess() {
        when(restTemplate.exchange(eq("https://zentao.example.com/api.php/v1/user"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));
        assertThat(client.testConnection().isReachable()).isTrue();
    }

    @Test
    @DisplayName("连通测试：401 归类为认证失败")
    void testConnectionUnauthorized() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        ConnectivityResult r = client.testConnection();
        assertThat(r.isReachable()).isFalse();
        assertThat(r.getMessage()).contains("认证失败");
    }

    @Test
    @DisplayName("新建缺陷：POST /products/{id}/bugs 并回写 bug 标识")
    void createBugIssue() {
        when(restTemplate.postForEntity(eq("https://zentao.example.com/api.php/v1/products/P1/bugs"),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"id\":10}", HttpStatus.CREATED));

        IssueRef ref = client.createIssue(IssuePayload.builder().type(IssueType.BUG).title("缺陷").build());
        assertThat(ref.getRemoteKey()).isEqualTo("bug-10");
        assertThat(ref.isCreated()).isTrue();
    }

    @Test
    @DisplayName("新建故事：POST /products/{id}/stories")
    void createStoryIssue() {
        when(restTemplate.postForEntity(eq("https://zentao.example.com/api.php/v1/products/P1/stories"),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"id\":5}", HttpStatus.CREATED));

        IssueRef ref = client.createIssue(IssuePayload.builder().type(IssueType.STORY).title("需求").build());
        assertThat(ref.getRemoteKey()).isEqualTo("story-5");
    }

    @Test
    @DisplayName("更新：PUT /bugs/{id} 幂等重推")
    void updateBugIssue() {
        when(restTemplate.exchange(eq("https://zentao.example.com/api.php/v1/bugs/10"),
                eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        IssueRef ref = client.updateIssue("bug-10", IssuePayload.builder().title("t").build());
        assertThat(ref.getRemoteKey()).isEqualTo("bug-10");
        assertThat(ref.isCreated()).isFalse();
    }

    @Test
    @DisplayName("状态流转：resolved -> POST /bugs/{id}/resolve")
    void transitionResolved() {
        when(restTemplate.postForEntity(eq("https://zentao.example.com/api.php/v1/bugs/10/resolve"),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        client.transitionStatus("bug-10", RemoteStatus.builder().status("RESOLVED").build());
        verify(restTemplate).postForEntity(eq("https://zentao.example.com/api.php/v1/bugs/10/resolve"),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("非法 remoteKey 格式抛出异常")
    void invalidRemoteKey() {
        assertThatThrownBy(() -> client.updateIssue("bad-key-format", IssuePayload.builder().build()))
                .isInstanceOf(RuntimeException.class);
    }
}
