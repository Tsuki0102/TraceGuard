package com.traceguard.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GAP-009：第三方工具集成配置
 * 密钥一律环境变量占位，与GAP-027风格一致
 */
@Data
@Component
@ConfigurationProperties(prefix = "traceguard.integration")
public class IntegrationProperties {
    /**
     * 单请求超时秒数
     */
    private Integer timeoutSeconds = 10;
    
    /**
     * 熔断阈值（连续失败次数）
     */
    private Integer circuitFailureThreshold = 5;
    
    /**
     * 熔断持续时间秒数
     */
    private Integer circuitOpenSeconds = 300;
    
    /**
     * Jira配置
     */
    private JiraConfig jira = new JiraConfig();
    
    /**
     * 禅道配置
     */
    private ZentaoConfig zentao = new ZentaoConfig();

    /**
     * 企业微信机器人配置
     */
    private WecomConfig wecom = new WecomConfig();

    /**
     * 钉钉机器人配置
     */
    private DingTalkConfig dingtalk = new DingTalkConfig();

    @Data
    public static class JiraConfig {
        /**
         * 是否启用（默认关闭，enabled=false主流程零感知）
         */
        private boolean enabled = false;
        /**
         * Jira服务地址
         */
        private String baseUrl = "${JIRA_BASE_URL:}";
        /**
         * 用户名
         */
        private String username = "${JIRA_USER:}";
        /**
         * API Token
         */
        private String token = "${JIRA_TOKEN:}";
        /**
         * 项目Key
         */
        private String projectKey = "${JIRA_PROJECT_KEY:}";
    }
    
    @Data
    public static class ZentaoConfig {
        /**
         * 是否启用（默认关闭）
         */
        private boolean enabled = false;
        /**
         * 禅道服务地址
         */
        private String baseUrl = "${ZENTAO_BASE_URL:}";
        /**
         * API Token
         */
        private String token = "${ZENTAO_TOKEN:}";
        /**
         * 产品ID
         */
        private String productId = "${ZENTAO_PRODUCT_ID:}";
    }

    @Data
    public static class WecomConfig {
        /**
         * 是否启用（默认关闭）
         */
        private boolean enabled = false;
        /**
         * 企业微信 API 服务地址（默认官方域名，演示/内网可覆盖）
         */
        private String baseUrl = "${WECOM_BASE_URL:https://qyapi.weixin.qq.com}";
        /**
         * 群机器人 Webhook Key
         */
        private String key = "${WECOM_KEY:}";
        /**
         * 加签密钥（可选，机器人开启加签时必填）
         */
        private String secret = "${WECOM_SECRET:}";
    }

    @Data
    public static class DingTalkConfig {
        /**
         * 是否启用（默认关闭）
         */
        private boolean enabled = false;
        /**
         * 钉钉开放平台服务地址（默认官方域名，演示/内网可覆盖）
         */
        private String baseUrl = "${DINGTALK_BASE_URL:https://oapi.dingtalk.com}";
        /**
         * 自定义机器人 AccessToken
         */
        private String accessToken = "${DINGTALK_TOKEN:}";
        /**
         * 加签密钥（可选，机器人开启加签时必填）
         */
        private String secret = "${DINGTALK_SECRET:}";
    }

    /**
     * GAP-009 入站同步（远程->本地）：定时轮询已推送缺陷在第三方平台的状态变化并回写本地。
     * 入站无需公网地址，仅后端定时拉取即可。
     */
    private InboundSyncConfig inboundSync = new InboundSyncConfig();

    @Data
    public static class InboundSyncConfig {
        /**
         * 是否启用入站轮询（默认关闭）
         */
        private boolean enabled = false;
        /**
         * 轮询 cron 表达式（默认每 5 分钟一次）
         */
        private String cron = "0 */5 * * * *";
        /**
         * 单次同步每平台最大缺陷数（防止一次性拉取过多），默认 200
         */
        private int batchSize = 200;
    }
}