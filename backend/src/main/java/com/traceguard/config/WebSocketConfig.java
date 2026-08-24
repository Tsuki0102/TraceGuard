package com.traceguard.config;

import com.traceguard.websocket.ProgressWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket配置：注册分析进度推送端点 /ws/progress
 * SEC-05：allowedOrigins 收敛为与 CorsConfig 一致的白名单，不再放行任意来源
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ProgressWebSocketHandler progressWebSocketHandler;

    @Value("${traceguard.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 白名单为空（prod 未注入）时传空数组 = 仅允许同源握手（fail-closed）
        String[] origins = allowedOrigins == null || allowedOrigins.trim().isEmpty()
                ? new String[0] : allowedOrigins.split(",");
        registry.addHandler(progressWebSocketHandler, "/ws/progress")
                .setAllowedOrigins(origins);
    }
}
