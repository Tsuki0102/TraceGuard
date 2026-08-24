package com.traceguard.common;

/**
 * 报告模板（FR-CHECK-005 自定义报告模板）：预置三种导出粒度
 * FULL 完整报告：全部章节（概况/统计/类型分布/缺陷明细/代码质量/追溯矩阵）
 * DEFECT_ONLY 缺陷聚焦：概况/统计/缺陷分级/缺陷明细/代码质量（不含类型分布与追溯矩阵）
 * BRIEF 简要报告：仅项目概况与统计
 */
public enum ReportTemplate {
    FULL, DEFECT_ONLY, BRIEF;

    /** 是否包含缺陷明细与代码质量章节（简要模板不含） */
    public boolean includeDefects() {
        return this != BRIEF;
    }

    /** 是否包含缺陷类型分布章节（仅完整模板） */
    public boolean includeTypeDistribution() {
        return this == FULL;
    }

    /** 是否包含追溯矩阵章节（仅完整模板） */
    public boolean includeTraceability() {
        return this == FULL;
    }
}
