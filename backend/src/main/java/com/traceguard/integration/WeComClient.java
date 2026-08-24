package com.traceguard.integration;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * GAP-009：企业微信群机器人 Webhook 通知实现
 * 发送：POST {baseUrl}/cgi-bin/webhook/send?key=KEY[&timestamp=..&sign=..]
 * 加签：机器人开启"加签"时配置 secret，算法 HMAC-SHA256(timestamp + "\n" + secret)。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "traceguard.integration.wecom", name = "enabled", havingValue = "true")
public class WeComClient extends AbstractWebhookClient {

    public WeComClient(RestTemplate restTemplate, IntegrationProperties properties) {
        super(restTemplate, properties);
    }

    @Override
    public ToolType type() {
        return ToolType.WECOM;
    }

    @Override
    public boolean enabled() {
        return properties.getWecom().isEnabled();
    }

    @Override
    protected String buildWebhookUrl() {
        return properties.getWecom().getBaseUrl() + "/cgi-bin/webhook/send?key=" + properties.getWecom().getKey();
    }

    @Override
    protected String applySign(String url) {
        String secret = properties.getWecom().getSecret();
        if (secret == null || secret.isEmpty() || secret.startsWith("${")) {
            return url; // 未开启加签
        }
        try {
            long ts = System.currentTimeMillis() / 1000;
            return url + "&timestamp=" + ts + "&sign=" + sign(secret, ts);
        } catch (Exception e) {
            log.warn("[WECOM] 加签计算失败，按未加签发送: {}", e.getMessage());
            return url;
        }
    }

    @Override
    protected String buildPayload(NotificationMessage message) throws Exception {
        String type = (message.getMsgType() == null || message.getMsgType().isEmpty())
                ? "text" : message.getMsgType();
        String content = withUrl(message.getContent(), message.getUrl());
        if ("markdown".equalsIgnoreCase(type)) {
            return "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":" + toJson(content) + "}}";
        }
        return "{\"msgtype\":\"text\",\"text\":{\"content\":" + toJson(content) + "}}";
    }

    @Override
    protected boolean isSuccess(JsonNode resp) {
        return resp.path("errcode").asInt(-1) == 0;
    }
}
