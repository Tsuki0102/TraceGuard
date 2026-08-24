package com.traceguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Soot 字节码级 CFG 配置（GAP-003/022）
 * 编译失败 / 单方法超时 / 任务级熔断 / 开关关闭时回退 AST 级 CfgBuilderUtil。
 */
@Component
@ConfigurationProperties(prefix = "traceguard.soot")
public class SootProperties {

    /** 是否启用 Soot 字节码 CFG（关闭即全量 AST CFG） */
    private boolean enabled = true;

    /** 源码批量编译超时（秒），独立线程中断 */
    private int compileTimeoutSeconds = 60;

    /** 单方法 CFG 构建超时（秒），超时该方法回退 AST */
    private int methodCfgTimeoutSeconds = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCompileTimeoutSeconds() { return compileTimeoutSeconds; }
    public void setCompileTimeoutSeconds(int compileTimeoutSeconds) { this.compileTimeoutSeconds = compileTimeoutSeconds; }
    public int getMethodCfgTimeoutSeconds() { return methodCfgTimeoutSeconds; }
    public void setMethodCfgTimeoutSeconds(int methodCfgTimeoutSeconds) { this.methodCfgTimeoutSeconds = methodCfgTimeoutSeconds; }
}
