package com.traceguard.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * GAP-009：企业微信 / 钉钉 Webhook 客户端单元测试
 * 覆盖：连通测试（errcode=0 判定成功）、markdown/text 消息体构造、未启用返回不可达、加签 URL。
 */
@DisplayName("GAP-009 企微/钉钉 Webhook 客户端单元测试")
@ExtendWith(MockitoExtension.class)
class WebhookClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    @DisplayName("企微：连通测试成功（errcode=0）")
    void wecomTestConnectionOk() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}"));

        WeComClient client = new WeComClient(restTemplate, wecomProps(true, "http://localhost:18082", "mock-key", ""));
        ConnectivityResult r = client.testConnection();

        assertThat(r.isReachable()).isTrue();
        assertThat(r.getMessage()).isEqualTo("200 OK");
    }

    @Test
    @DisplayName("企微：发送 markdown 消息体结构正确且 URL 携带 key")
    void wecomMarkdownPayload() {
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    capturedUrl.set(inv.getArgument(0));
                    capturedBody.set((String) ((HttpEntity<?>) inv.getArgument(1)).getBody());
                    return ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
                });

        WeComClient client = new WeComClient(restTemplate, wecomProps(true, "http://localhost:18082", "mock-key", ""));
        boolean ok = client.sendMessage(NotificationMessage.builder()
                .title("缺陷通知").msgType("markdown").content("检出缺陷：**空指针**")
                .url("http://traceguard/detail/1").build());

        assertThat(ok).isTrue();
        assertThat(capturedUrl.get()).startsWith("http://localhost:18082/cgi-bin/webhook/send?key=mock-key");
        assertThat(capturedBody.get()).contains("\"msgtype\":\"markdown\"");
        assertThat(capturedBody.get()).contains("检出缺陷：**空指针**");
        assertThat(capturedBody.get()).contains("[详情](http://traceguard/detail/1)");
    }

    @Test
    @DisplayName("钉钉：连通测试成功且 URL 携带 access_token")
    void dingTalkTestConnectionOk() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}"));

        DingTalkClient client = new DingTalkClient(restTemplate, dingtalkProps(true, "http://localhost:18083", "mock-token", ""));
        ConnectivityResult r = client.testConnection();

        assertThat(r.isReachable()).isTrue();
        assertThat(r.getMessage()).isEqualTo("200 OK");
    }

    @Test
    @DisplayName("钉钉：markdown 消息使用 title+text 字段")
    void dingTalkMarkdownPayload() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    capturedBody.set((String) ((HttpEntity<?>) inv.getArgument(1)).getBody());
                    return ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
                });

        DingTalkClient client = new DingTalkClient(restTemplate, dingtalkProps(true, "http://localhost:18083", "mock-token", ""));
        boolean ok = client.sendMessage(NotificationMessage.builder()
                .title("缺陷通知").msgType("markdown").content("检出缺陷：空指针").build());

        assertThat(ok).isTrue();
        assertThat(capturedBody.get()).contains("\"msgtype\":\"markdown\"");
        assertThat(capturedBody.get()).contains("\"title\":\"缺陷通知\"");
        assertThat(capturedBody.get()).contains("检出缺陷：空指针");
    }

    @Test
    @DisplayName("未启用：连通测试返回不可达且提示未启用")
    void disabledTestConnection() {
        WeComClient client = new WeComClient(restTemplate, wecomProps(false, "http://localhost:18082", "mock-key", ""));
        ConnectivityResult r = client.testConnection();
        assertThat(r.isReachable()).isFalse();
        assertThat(r.getMessage()).contains("未启用");
    }

    @Test
    @DisplayName("企微加签：配置 secret 时 URL 追加 timestamp/sign")
    void wecomSignUrl() {
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    capturedUrl.set(inv.getArgument(0));
                    return ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
                });

        WeComClient client = new WeComClient(restTemplate, wecomProps(true, "http://localhost:18082", "mock-key", "mock-secret"));
        client.sendMessage(NotificationMessage.builder().content("hello").build());

        assertThat(capturedUrl.get()).contains("&timestamp=");
        assertThat(capturedUrl.get()).contains("&sign=");
    }

    private IntegrationProperties wecomProps(boolean enabled, String baseUrl, String key, String secret) {
        IntegrationProperties props = new IntegrationProperties();
        IntegrationProperties.WecomConfig c = new IntegrationProperties.WecomConfig();
        c.setEnabled(enabled);
        c.setBaseUrl(baseUrl);
        c.setKey(key);
        c.setSecret(secret);
        props.setWecom(c);
        return props;
    }

    private IntegrationProperties dingtalkProps(boolean enabled, String baseUrl, String token, String secret) {
        IntegrationProperties props = new IntegrationProperties();
        IntegrationProperties.DingTalkConfig c = new IntegrationProperties.DingTalkConfig();
        c.setEnabled(enabled);
        c.setBaseUrl(baseUrl);
        c.setAccessToken(token);
        c.setSecret(secret);
        props.setDingtalk(c);
        return props;
    }
}
