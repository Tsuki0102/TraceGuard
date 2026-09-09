package com.traceguard.agent;

import java.util.Map;

/**
 * 智能体工具接口（只读工具，白名单式注册）
 * 工具即系统既有 Service 能力的封装；数据权限由各工具内部依赖 UserContext 校验
 * （AgentService 在工作线程上恢复用户上下文后调用）。
 */
public interface AgentTool {

    /** 工具名（模型调用时引用，小写下划线风格） */
    String name();

    /** 工具能力描述（注入 Agent 系统提示词） */
    String description();

    /** 参数说明（JSON 对象描述文本，注入 Agent 系统提示词） */
    String argsSchema();

    /**
     * 执行工具
     * @param args 模型给出的参数（可能缺失/类型不齐，实现需容错）
     * @return 可 JSON 序列化的结果对象（Map/List/标量）
     * @throws Exception 业务异常（如无权限），AgentService 捕获后作为观察结果回喂模型
     */
    Object execute(Map<String, Object> args) throws Exception;

    /**
     * 是否为动作类（写操作）工具：为 true 时不直接执行，
     * 由 AgentService 生成待确认动作（PendingAction），用户在前端确认后才执行（human-in-the-loop）。
     */
    default boolean requiresConfirm() {
        return false;
    }

    /** 确认卡片上的中文操作摘要（动作类工具须覆写） */
    default String confirmSummary(Map<String, Object> args) {
        return String.valueOf(args);
    }
}
