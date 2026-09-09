package com.traceguard.agent;

import com.traceguard.config.LlmProperties;
import com.traceguard.service.AuditService;
import com.traceguard.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 质量巡检编排（三期）
 * 复用 Agent ReAct 循环无头执行巡检提示词（admin 视角，只读工具），
 * 报告落点：审计日志（AGENT_PATROL，经哈希链标准写入）+ 进程内最新报告缓存（/agent/patrol/latest 可查）。
 * 定时触发由 traceguard.agent.patrol.enabled/cron 控制（默认关闭，控 LLM 成本）；管理员手动触发不受开关限制。
 */
@Component
public class AgentPatrolService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentPatrolService.class);

    /** 巡检提示词（只读巡检，动作类工具被确认机制天然拦截 + 提示词显式禁止） */
    private static final String PATROL_PROMPT =
            "请执行一次 TraceGuard 质量巡检（只读模式，禁止调用动作类工具）：\n"
            + "1) 用 list_projects 和 get_overview 了解全部项目概况；\n"
            + "2) 对缺陷较多或状态异常的项目，用 query_defects 检查 serious 级且状态为 pending/processing 的缺陷；\n"
            + "3) 用 get_trend(days=14) 查看近两周缺陷趋势有无异动；\n"
            + "4) 最后输出 Markdown 巡检报告，包含四节：总体状态、需关注项目（附关键数据）、风险点、建议行动；全文不超过 400 字。";

    /** 巡检最大决策步数（比对话放宽，覆盖多项目检查） */
    private static final int PATROL_MAX_STEPS = 8;

    @Autowired
    private AgentService agentService;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private AuditService auditService;

    /** 互斥：同一时刻仅一个巡检在跑 */
    private final AtomicBoolean running = new AtomicBoolean();
    /** 最新报告缓存（进程内；重启后清空，重新巡检即可） */
    private volatile String latestReport;
    private volatile LocalDateTime latestTime;
    private volatile String latestTrigger;

    /**
     * 执行一次巡检（同步，可能耗时 30~120s）
     *
     * @param trigger  触发来源：scheduled / manual
     * @param userId/username 审计归属（定时任务传 0L/system）
     * @return {success, report|message, time?, costMs?}
     */
    public Map<String, Object> runPatrol(String trigger, Long userId, String username) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!llmProperties.isEnabled()) {
            out.put("success", false);
            out.put("message", "大模型未启用，巡检不可用。请先在「系统配置-大模型配置」中启用。");
            return out;
        }
        if (!running.compareAndSet(false, true)) {
            out.put("success", false);
            out.put("message", "巡检正在执行中，请稍候");
            return out;
        }
        long startMs = System.currentTimeMillis();
        try {
            String report = agentService.runHeadless(
                    "patrol-" + System.currentTimeMillis(), PATROL_PROMPT, PATROL_MAX_STEPS);
            if (report == null || report.isBlank()) {
                throw new IllegalStateException("巡检未产出报告");
            }
            long costMs = System.currentTimeMillis() - startMs;
            latestReport = report;
            latestTime = LocalDateTime.now();
            latestTrigger = trigger;
            auditRecord(userId, username, trigger, costMs, null);
            LOGGER.info("质量巡检完成（trigger={}, cost={}ms，报告 {} 字）", trigger, costMs, report.length());
            out.put("success", true);
            out.put("report", report);
            out.put("time", latestTime.toString());
            out.put("costMs", costMs);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startMs;
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            auditRecord(userId, username, trigger, costMs, err);
            LOGGER.warn("质量巡检失败（trigger={}）: {}", trigger, err);
            out.put("success", false);
            out.put("message", "巡检失败：" + err);
        } finally {
            running.set(false);
        }
        return out;
    }

    /** 最近一次巡检报告（未执行过返回空标记） */
    public Map<String, Object> latest() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (latestReport == null) {
            out.put("success", false);
            out.put("message", "尚未执行过巡检（定时任务默认关闭，可手动触发）");
            return out;
        }
        out.put("success", true);
        out.put("report", latestReport);
        out.put("time", latestTime.toString());
        out.put("trigger", latestTrigger);
        return out;
    }

    /** 巡检审计落库（走 AuditService.record，维护哈希链；异步，异常静默） */
    private void auditRecord(Long userId, String username, String trigger, long costMs, String errorMsg) {
        auditService.record(userId == null ? 0L : userId,
                username == null ? "system" : username,
                "AGENT_PATROL",
                "AgentPatrolService.runPatrol",
                "/agent/patrol",
                "trigger=" + trigger,
                null,
                errorMsg == null ? 200 : 500,
                costMs,
                errorMsg);
    }
}
