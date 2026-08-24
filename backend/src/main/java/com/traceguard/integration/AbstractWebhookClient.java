package com.traceguard.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * GAP-009：Webhook 通知客户端抽象基类（企业微信 / 钉钉群机器人共用）
 * 统一实现"开关 + 超时 + 熔断 + 兜底"与 HMAC-SHA256 加签；子类仅需提供
 * Webhook URL 构造、消息体构造与成功判定。
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractWebhookClient implements WebhookClient {

    protected final RestTemplate restTemplate;
    protected final IntegrationProperties properties;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    // 熔断状态（任务级，内存实现）
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenTime = 0;
    private volatile int failureCount = 0;

    @Override
    public boolean sendMessage(NotificationMessage message) {
        checkCircuit();
        try {
            String url = applySign(buildWebhookUrl());
            String body = buildPayload(message);
            HttpEntity<String> entity = new HttpEntity<>(body, jsonHeaders());
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            JsonNode node = objectMapper.readTree(resp.getBody());
            if (isSuccess(node)) {
                resetFailureCount();
                log.info("[{}] 通知发送成功: {}", type(), message.getTitle());
                return true;
            }
            recordFailure(new RuntimeException(type() + " 返回失败码: " + node.path("errcode").asInt()));
            return false;
        } catch (Exception e) {
            recordFailure(e);
            log.warn("[{}] 通知发送失败: {}", type(), e.getMessage());
            return false;
        }
    }

    @Override
    public ConnectivityResult testConnection() {
        if (!enabled()) {
            return ConnectivityResult.builder()
                    .toolType(type()).reachable(false)
                    .message("未启用（未配置环境变量或 enabled=false）").build();
        }
        try {
            boolean ok = sendMessage(NotificationMessage.builder()
                    .title("TraceGuard 连通测试")
                    .msgType("text")
                    .content("TraceGuard 连通测试成功，本消息由后端自动发送。").build());
            if (ok) {
                resetFailureCount();
            }
            return ConnectivityResult.builder()
                    .toolType(type()).reachable(ok)
                    .message(ok ? "200 OK" : "发送失败，请检查机器人配置").build();
        } catch (Exception e) {
            return ConnectivityResult.builder()
                    .toolType(type()).reachable(false).message(e.getMessage()).build();
        }
    }

    // ==================== 子类需实现 ====================

    /** 构造基础 Webhook URL（含 key/access_token 参数，不含加签） */
    protected abstract String buildWebhookUrl();

    /** 配置了加签 secret 时在 URL 上追加 timestamp/sign；未配置返回原样 */
    protected abstract String applySign(String url);

    /** 构造消息 JSON（text / markdown） */
    protected abstract String buildPayload(NotificationMessage message) throws Exception;

    /** 判定响应是否成功（errcode == 0） */
    protected abstract boolean isSuccess(JsonNode resp);

    // ==================== 共用工具 ====================

    protected String toJson(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    /** HMAC-SHA256 十六进制签名（企微/钉钉加签口径一致） */
    protected String sign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    protected String withUrl(String content, String url) {
        if (url == null || url.isEmpty()) return content;
        return content + "\n[详情](" + url + ")";
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private void checkCircuit() {
        if (circuitOpen) {
            long elapsed = System.currentTimeMillis() - circuitOpenTime;
            if (elapsed < properties.getCircuitOpenSeconds() * 1000L) {
                throw new RuntimeException(type() + " 集成暂不可用（熔断中），请稍后重试");
            } else {
                circuitOpen = false; // 半开状态，允许试探
            }
        }
    }

    private void recordFailure(Exception e) {
        failureCount++;
        if (failureCount >= properties.getCircuitFailureThreshold()) {
            circuitOpen = true;
            circuitOpenTime = System.currentTimeMillis();
            log.warn("[{}] 集成熔断触发，连续失败 {} 次", type(), failureCount);
        }
    }

    private void resetFailureCount() {
        failureCount = 0;
        circuitOpen = false;
    }
}
