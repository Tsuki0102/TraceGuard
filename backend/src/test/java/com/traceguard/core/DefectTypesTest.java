package com.traceguard.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-4：缺陷主类型映射（DefectTypes + DefectSubType 枚举化后行为保持一致）。
 */
@DisplayName("DefectTypes 主类型映射")
class DefectTypesTest {

    @Test
    @DisplayName("判定链路子类型 -> 主类型映射保持历史口径")
    void determineSubTypesMapToMainTypes() {
        assertThat(DefectTypes.toMainType("约束条件不满足")).isEqualTo(DefectTypes.CONSTRAINT_VIOLATION);
        assertThat(DefectTypes.toMainType("不变量不满足")).isEqualTo(DefectTypes.CONSTRAINT_VIOLATION);
        assertThat(DefectTypes.toMainType("逻辑偏离")).isEqualTo(DefectTypes.LOGIC_MISMATCH);
        assertThat(DefectTypes.toMainType("缺失实现")).isEqualTo(DefectTypes.MISSING_REQUIREMENT);
        assertThat(DefectTypes.toMainType("超范围实现")).isEqualTo(DefectTypes.OVER_IMPLEMENTATION);
        assertThat(DefectTypes.toMainType("冗余实现")).isEqualTo(DefectTypes.OVER_IMPLEMENTATION);
        assertThat(DefectTypes.toMainType("异常处理缺失")).isEqualTo(DefectTypes.CONSTRAINT_VIOLATION);
        assertThat(DefectTypes.toMainType("资源管理缺失")).isEqualTo(DefectTypes.CONSTRAINT_VIOLATION);
    }

    @Test
    @DisplayName("需求代码不匹配：按分项得分动态归入")
    void reqCodeMismatchDynamicMain() {
        // inv < con -> 约束证据缺口 -> 约束条件不满足
        assertThat(DefectTypes.toMainType("需求代码不匹配", 0.3, 0.7)).isEqualTo(DefectTypes.CONSTRAINT_VIOLATION);
        // inv >= con -> 业务逻辑不一致
        assertThat(DefectTypes.toMainType("需求代码不匹配", 0.7, 0.4)).isEqualTo(DefectTypes.LOGIC_MISMATCH);
    }

    @Test
    @DisplayName("空串/未知历史值兜底（空串返回空，未知归业务逻辑不一致）")
    void edgeFallbacks() {
        assertThat(DefectTypes.toMainType("")).isEmpty();
        assertThat(DefectTypes.toMainType(null, 0.5, 0.5)).isEmpty();
        assertThat(DefectTypes.toMainType("历史遗留未知子类型")).isEqualTo(DefectTypes.LOGIC_MISMATCH);
    }

    @Test
    @DisplayName("枚举 label 与 DefectTypes 常量一致（存量数据兼容的唯一出口）")
    void enumLabelsAlign() {
        assertThat(DefectSubType.MISSING_REQUIREMENT.label()).isEqualTo(DefectTypes.MISSING_REQUIREMENT);
        assertThat(DefectSubType.OVER_IMPLEMENTATION.label()).isEqualTo(DefectTypes.OVER_IMPLEMENTATION);
        assertThat(DefectSubType.LOGIC_MISMATCH.label()).isEqualTo(DefectTypes.LOGIC_MISMATCH);
        assertThat(DefectSubType.CONSTRAINT_UNMET.label()).isEqualTo(DefectTypes.CONSTRAINT_VIOLATION);
    }
}
