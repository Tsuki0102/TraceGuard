package com.traceguard.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时质量巡检任务（三期）
 * 默认关闭（traceguard.agent.patrol.enabled=false），避免无人值守时的 LLM 调用成本；
 * 开启后按 cron（默认每日 09:00）以系统身份执行巡检，报告落审计日志并缓存至 /agent/patrol/latest。
 */
@Component
public class AgentPatrolJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentPatrolJob.class);

    @Value("${traceguard.agent.patrol.enabled:false}")
    private boolean enabled;

    @Autowired
    private AgentPatrolService patrolService;

    /** 定时巡检（系统身份执行，数据全量可见） */
    @Scheduled(cron = "${traceguard.agent.patrol.cron:0 0 9 * * ?}")
    public void scheduledPatrol() {
        if (!enabled) {
            return;
        }
        try {
            patrolService.runPatrol("scheduled", 0L, "system");
        } catch (Exception e) {
            LOGGER.warn("定时巡检执行异常: {}", e.getMessage(), e);
        }
    }
}
