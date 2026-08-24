package com.traceguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * CQ-06：缺陷判定子阈值配置持有器（消除 determineDefectType 魔法值）。
 *
 * 与 {@link RuleConfigHolder} 同模式：静态持有运行时值 + Spring 启动注入。
 * - Spring 环境：从 {@code traceguard.analysis.defect-thresholds.*} 注入（默认值与历史行为一致，仅消除魔法值）；
 * - 评测/测试直接 new（非 Spring）时静态默认值生效（0.6/0.7/0.2），保证行为不变。
 */
@Data
@Component
@ConfigurationProperties(prefix = "traceguard.analysis.defect-thresholds")
public class ThresholdConfigHolder {

    /** 子类型判定：conMatch < 该值 -> 约束条件不满足 */
    private double conMatchMin = 0.6;
    /** 子类型判定：invSat < 该值 -> 不变量不满足 */
    private double invSatMin = 0.7;
    /** 子类型判定：cosSim < 该值 -> 逻辑偏离 */
    private double cosSimMin = 0.2;

    private static volatile double CON_MATCH = 0.6;
    private static volatile double INV_SAT = 0.7;
    private static volatile double COS_SIM = 0.2;

    @PostConstruct
    public void applyToStatic() {
        CON_MATCH = conMatchMin;
        INV_SAT = invSatMin;
        COS_SIM = cosSimMin;
    }

    public static double conMatchMin() { return CON_MATCH; }
    public static double invSatMin() { return INV_SAT; }
    public static double cosSimMin() { return COS_SIM; }
}
