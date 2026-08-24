package com.traceguard.integration;

/**
 * GAP-009：Webhook 通知客户端抽象（企业微信 / 钉钉群机器人）
 * 仅出站推送，不维护 issue 生命周期；连通测试即发送一条测试消息。
 */
public interface WebhookClient extends ToolClient {
    /**
     * 发送一条通知
     * @param message 消息体（text / markdown）
     * @return 是否发送成功
     */
    boolean sendMessage(NotificationMessage message);
}
