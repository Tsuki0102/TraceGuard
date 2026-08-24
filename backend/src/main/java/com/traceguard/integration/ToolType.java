package com.traceguard.integration;

/**
 * GAP-009：第三方集成工具类型枚举
 * JIRA/ZENTAO 为项目管理工具（创建/更新 issue）；WECOM/DINGTALK 为 Webhook 通知机器人。
 */
public enum ToolType {
    JIRA,
    ZENTAO,
    WECOM,
    DINGTALK
}
