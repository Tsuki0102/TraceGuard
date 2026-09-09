package com.traceguard.controller;

import com.traceguard.agent.AgentPatrolService;
import com.traceguard.agent.AgentService;
import com.traceguard.agent.AgentTools;
import com.traceguard.common.Result;
import com.traceguard.config.LlmProperties;
import com.traceguard.util.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体接口（一期：只读工具 + SSE 流式对话）
 * 登录即可用（数据权限跟随用户身份，工具内部校验）；LLM 总开关关闭时返回明确错误。
 */
@RestController
@RequestMapping("/agent")
@Api(tags = "11-智能体助手")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentPatrolService patrolService;

    @Autowired
    private AgentTools agentTools;

    @Autowired
    private LlmProperties llmProperties;

    /** 智能体状态（前端入口展示与降级判断用） */
    @ApiOperation(value = "智能体状态", notes = "返回 LLM 启用状态与可用工具清单")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", llmProperties.isEnabled());
        out.put("tools", agentTools.names());
        return Result.success(out);
    }

    /**
     * 流式对话（SSE）
     * 事件流：status（thinking/tool 进度）-> delta*（最终回答分片）-> done；异常时 error。
     * 登录拦截器已校验 JWT；此处捕获用户上下文供异步线程恢复（保持数据权限语义）。
     */
    @ApiOperation(value = "智能体流式对话", notes = "POST SSE；请求体 {sessionId, message}；事件 status/delta/done/error")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String message = body.getOrDefault("message", "").trim();
        // 请求线程捕获用户上下文（异步工作线程无法继承 ThreadLocal）
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        String role = UserContext.getRole();
        return agentService.chatStream(sessionId, message, userId, username, role);
    }

    /** 重置会话（清空服务端历史） */
    @ApiOperation(value = "重置智能体会话", notes = "请求体 {sessionId}；清空该会话的多轮历史")
    @PostMapping("/reset")
    public Result<Void> reset(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        if (!sessionId.isEmpty()) {
            agentService.resetSession(sessionId);
        }
        return Result.success(null);
    }

    /**
     * 确认/取消待确认动作（human-in-the-loop）
     * 请求体 {sessionId, token, approve}；动作仅在 approve=true 时执行，权限沿用当前登录用户。
     */
    @ApiOperation(value = "确认执行待确认动作", notes = "请求体 {sessionId, token, approve}；返回 {success, message}")
    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String token = String.valueOf(body.getOrDefault("token", ""));
        boolean approve = Boolean.parseBoolean(String.valueOf(body.getOrDefault("approve", "false")));
        return Result.success(agentService.confirmAction(sessionId, token, approve));
    }

    // ==================== 三期：质量巡检 ====================

    /** 仅管理员校验（巡检消耗 LLM 配额） */
    private Result<Void> checkAdmin() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "无权限：仅管理员可操作质量巡检");
        }
        return null;
    }

    /** 手动触发质量巡检（同步执行，LLM 多步调用可能耗时 30~120s） */
    @ApiOperation(value = "手动触发质量巡检", notes = "仅管理员；同步执行，返回 {success, report|message}")
    @PostMapping("/patrol/run")
    public Result<Map<String, Object>> patrolRun() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(patrolService.runPatrol("manual", UserContext.getUserId(), UserContext.getUsername()));
    }

    /** 最近一次巡检报告（仅管理员） */
    @ApiOperation(value = "最近巡检报告", notes = "仅管理员；返回 {success, report, time, trigger}")
    @GetMapping("/patrol/latest")
    public Result<Map<String, Object>> patrolLatest() {
        Result<Void> denied = checkAdmin();
        if (denied != null) return Result.error(denied.getCode(), denied.getMessage());
        return Result.success(patrolService.latest());
    }
}
