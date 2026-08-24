package com.traceguard.integration;

/**
 * GAP-009：第三方集成客户端统一抽象（工具类型 / 启用开关 / 连通测试）
 * ProjectToolClient（项目管理工具）与 WebhookClient（通知机器人）均继承本接口，
 * 使 IntegrationController 的连通测试/状态总览可统一处理。
 */
public interface ToolClient {
    /**
     * 工具类型
     */
    ToolType type();

    /**
     * 是否启用
     */
    boolean enabled();

    /**
     * 连通性测试（验证认证与可达性；Webhook 机器人发一条测试消息）
     */
    ConnectivityResult testConnection();
}
