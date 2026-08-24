package com.traceguard.integration;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GAP-009：钉钉自定义机器人 Webhook 通知实现
 * 发送：POST {baseUrl}/robot/send?access_token=TOKEN[&timestamp=..&sign=..]
 * 加签：机器人开启"加签"时配置 secret，算法 HMAC-SHA256(timestamp + "\n" + secret)，sign 需 URL 编码。
 * 消息：text / markdown（markdown 使用 title + text 字段）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "traceguard.integration.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkClient extends AbstractWebhookClient {

    public DingTalkClient(RestTemplate restTemplate, IntegrationProperties properties) {
        super(restTemplate, properties);
    }

    @Override
    public ToolType type() {
        return ToolType.DINGTALK;
    }

    @Override
    public boolean enabled() {
        return properties.getDingtalk().isEnabled();
    }

    @Override
    protected String buildWebhookUrl() {
        return properties.getDingtalk().getBaseUrl() + "/robot/send?access_token="
                + properties.getDingtalk().getAccessToken();
    }

    @Override
    protected String applySign(String url) {
        String secret = properties.getDingtalk().getSecret();
        if (secret == null || secret.isEmpty() || secret.startsWith("${")) {
            return url; // 未开启加签
        }
        try {
            long ts = System.currentTimeMillis() / 1000;
            String rawSign = sign(secret, ts);
            String encoded = URLEncoder.encode(rawSign, StandardCharsets.UTF_8);
            return url + "&timestamp=" + ts + "&sign=" + encoded;
        } catch (Exception e) {
            log.warn("[DINGTALK] 加签计算失败，按未加签发送: {}", e.getMessage());
            return url;
        }
    }

    @Override
    protected String buildPayload(NotificationMessage message) throws Exception {
        String type = (message.getMsgType() == null || message.getMsgType().isEmpty())
                ? "text" : message.getMsgType();
        String content = withUrl(message.getContent(), message.getUrl());
        if ("markdown".equalsIgnoreCase(type)) {
            String title = (message.getTitle() == null || message.getTitle().isEmpty())
                    ? "TraceGuard 通知" : message.getTitle();
            return "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":" + toJson(title)
                    + ",\"text\":" + toJson(content) + "}}";
        }
        return "{\"msgtype\":\"text\",\"text\":{\"content\":" + toJson(content) + "}}";
    }

    @Override
    protected boolean isSuccess(JsonNode resp) {
        return resp.path("errcode").asInt(-1) == 0;
    }
}
