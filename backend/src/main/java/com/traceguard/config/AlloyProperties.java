package com.traceguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Alloy Analyzer 真实求解配置（GAP-002/022）
 * 开关关闭 / 求解异常 / 超时 / 任务级熔断时自动回退自研结构校验，系统行为与改造前一致。
 */
@Component
@ConfigurationProperties(prefix = "traceguard.alloy")
public class AlloyProperties {

    /** 是否启用真实 Alloy 语义求解（关闭即回退纯结构校验） */
    private boolean enabled = true;

    /** 单次求解超时（秒），FutureTask 中断求解线程 */
    private int timeoutSeconds = 30;

    /** 实例迭代上限（SAT 时收集反例/实例数，保护内存） */
    private int maxInstances = 5;

    /** 分析作用域上限（收敛搜索空间，避免组合爆炸） */
    private int maxScope = 6;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = maxInstances; }
    public int getMaxScope() { return maxScope; }
    public void setMaxScope(int maxScope) { this.maxScope = maxScope; }
}
