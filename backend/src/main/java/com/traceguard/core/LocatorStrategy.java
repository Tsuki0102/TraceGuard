package com.traceguard.core;

/**
 * P2-4：缺陷行号定位策略（与 {@link DefectSubType} 绑定，替代原"中文子类型字符串 contains 分支"）。
 *
 * 每种策略对应 {@link DefectLocator} 中的一种 AST 定位算法；AST 解析失败时逐级降级为关键词启发式。
 */
public enum LocatorStrategy {

    /**
     * 约束/校验缺失：定位到方法体首条语句所在行（语义为"应在该行之前插入校验"）。
     */
    MISSING_VALIDATION,

    /**
     * 数值/阈值不一致：定位 BinaryExpr 比较中与该需求数值冲突的数值字面量所在行。
     */
    NUMERIC_LITERAL,

    /**
     * 状态流转/不变量违反：定位状态字段被赋值的行（setStatus(...)/status = ...）。
     */
    STATE_FIELD,

    /**
     * 逻辑偏离/通用业务不一致：定位方法体首条可执行语句。
     */
    FIRST_STATEMENT,

    /**
     * 需求缺失/超范围实现：定位到方法签名行（无更细粒度证据，方法整体即为定位对象）。
     */
    METHOD_START
}
