package com.traceguard.integration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GAP-009：Webhook 通知消息体（企业微信 / 钉钉机器人）
 * msgType 支持 text / markdown；markdown 时钉钉使用 title 作为消息标题。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {
    /**
     * 消息标题（钉钉 markdown 必填；企微可忽略）
     */
    private String title;
    /**
     * 消息正文（text 或 markdown）
     */
    private String content;
    /**
     * 详情链接（附加到正文尾部，可选）
     */
    private String url;
    /**
     * 消息类型：text / markdown，默认 text
     */
    private String msgType;
}
