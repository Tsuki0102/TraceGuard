package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.util.UserContext;
import com.traceguard.websocket.WSTicketService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * SEC-10：WebSocket 一次性连接票据接口。
 * 前端建立 /ws/progress 前先调用本接口换取 ticket（登录态经 LoginInterceptor 校验），
 * 避免 JWT 经 WS URL query 传递（进代理/访问日志）。
 */
@RestController
@RequestMapping("/ws")
@Api(tags = "WebSocket 票据")
public class WsTicketController {

    @Autowired
    private WSTicketService wsTicketService;

    @ApiOperation(value = "获取 WebSocket 一次性连接票据", notes = "登录用户调用；票据 30 秒内单次有效，不可复用")
    @GetMapping("/ticket")
    public Result<Map<String, String>> ticket() {
        String ticket = wsTicketService.issue(UserContext.getUserId());
        if (ticket == null) {
            return Result.error("WebSocket 连接请求过多，请稍后重试");
        }
        Map<String, String> data = new HashMap<>();
        data.put("ticket", ticket);
        return Result.success(data);
    }
}
