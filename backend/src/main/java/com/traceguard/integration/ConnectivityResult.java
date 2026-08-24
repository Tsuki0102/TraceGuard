package com.traceguard.integration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GAP-009：连通测试结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectivityResult {
    /**
     * 工具类型
     */
    private ToolType toolType;
    /**
     * 是否可达
     */
    private boolean reachable;
    /**
     * 结果消息（200 OK / 错误详情）
     */
    private String message;
    /**
     * 版本信息（可选）
     */
    private String version;
    /**
     * 服务地址（仅 status 接口返回；test 响应中为 null）
     */
    private String baseUrl;
    /**
     * 项目标识：Jira=projectKey，禅道=productId（仅 status 返回）
     */
    private String projectKey;
    /**
     * Token 是否已配置（仅 status 返回，避免泄露 token 原文）
     */
    private Boolean tokenConfigured;
}