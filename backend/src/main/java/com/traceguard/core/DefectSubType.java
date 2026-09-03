package com.traceguard.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P2-4：缺陷子类型枚举化（消除中文字符串 {@code contains} 分支的"字符串类型代码"坏味道）。
 *
 * 设计要点：
 * <ol>
 *   <li>{@link #label} 为唯一中文出口（对外展示 + 落库 + 前端取值），改名只需改这里；</li>
 *   <li>判定/定位/导出/展示一律走枚举，编译器可捕获遗漏的 {@code switch} 分支；</li>
 *   <li>存量数据兼容：库里存的仍是 {@link #label} 中文值，{@link #fromLabel(String)} 双读即可，无需数据迁移；
 *       未知历史值返回 null，由调用方回退到 {@link DefectTypes#LOGIC_MISMATCH} 等既有兜底逻辑。</li>
 * </ol>
 */
public enum DefectSubType {

    // ==================== 主类型即子类型（generateDefects 直接产出 / LLM 判定口径） ====================
    MISSING_REQUIREMENT("需求缺失", DefectTypes.MISSING_REQUIREMENT, LocatorStrategy.METHOD_START),
    OVER_IMPLEMENTATION("代码超范围实现", DefectTypes.OVER_IMPLEMENTATION, LocatorStrategy.METHOD_START),
    LOGIC_MISMATCH("业务逻辑不一致", DefectTypes.LOGIC_MISMATCH, LocatorStrategy.FIRST_STATEMENT),
    CONSTRAINT_UNMET("约束条件不满足", DefectTypes.CONSTRAINT_VIOLATION, LocatorStrategy.MISSING_VALIDATION),

    // ==================== 判定链路细分（ConsistencyChecker#determineDefectType 产出） ====================
    INVARIANT_VIOLATION("不变量不满足", DefectTypes.CONSTRAINT_VIOLATION, LocatorStrategy.STATE_FIELD),
    LOGIC_DEVIATION("逻辑偏离", DefectTypes.LOGIC_MISMATCH, LocatorStrategy.FIRST_STATEMENT),
    NUMERIC_BOUND("数值越界", DefectTypes.CONSTRAINT_VIOLATION, LocatorStrategy.NUMERIC_LITERAL),
    /** 主类型按分项得分动态归入（inv &lt; con -> 约束条件不满足，否则业务逻辑不一致），见 {@link #mainType(double, double)} */
    REQ_CODE_MISMATCH("需求代码不匹配", null, LocatorStrategy.FIRST_STATEMENT),

    // ==================== 历史遗留子类型（存量数据/前端筛选兼容，读多写少） ====================
    MISSING_IMPLEMENTATION("缺失实现", DefectTypes.MISSING_REQUIREMENT, LocatorStrategy.METHOD_START),
    OVER_SCOPE("超范围实现", DefectTypes.OVER_IMPLEMENTATION, LocatorStrategy.METHOD_START),
    REDUNDANT_IMPLEMENTATION("冗余实现", DefectTypes.OVER_IMPLEMENTATION, LocatorStrategy.METHOD_START),
    DATA_CONSISTENCY_RISK("数据一致性风险", DefectTypes.LOGIC_MISMATCH, LocatorStrategy.FIRST_STATEMENT),
    SECURITY_RISK("安全风险", DefectTypes.LOGIC_MISMATCH, LocatorStrategy.FIRST_STATEMENT),
    EXCEPTION_HANDLING_MISSING("异常处理缺失", DefectTypes.CONSTRAINT_VIOLATION, LocatorStrategy.MISSING_VALIDATION),
    RESOURCE_MANAGEMENT_MISSING("资源管理缺失", DefectTypes.CONSTRAINT_VIOLATION, LocatorStrategy.FIRST_STATEMENT);

    private static final Map<String, DefectSubType> BY_LABEL = new LinkedHashMap<>();
    static {
        for (DefectSubType t : values()) {
            BY_LABEL.put(t.label, t);
        }
    }

    /** 中文展示名（唯一出口） */
    public final String label;
    /** 固定主类型；为 null 表示需按分项得分动态归入（见 {@link #mainType(double, double)}） */
    public final String mainType;
    /** 行号定位策略 */
    public final LocatorStrategy locator;

    DefectSubType(String label, String mainType, LocatorStrategy locator) {
        this.label = label;
        this.mainType = mainType;
        this.locator = locator;
    }

    /** 中文展示名（唯一出口，改这里即可） */
    public String label() {
        return label;
    }

    public LocatorStrategy locator() {
        return locator;
    }

    /**
     * 主类型归类。固定主类型直接返回；{@link #REQ_CODE_MISMATCH} 按不变量/约束分项得分动态归入
     * （inv &lt; con 视为约束证据缺口 -> 约束条件不满足，否则业务逻辑不一致）。
     */
    public String mainType(double invariantScore, double constraintScore) {
        if (mainType != null) {
            return mainType;
        }
        return invariantScore < constraintScore
                ? DefectTypes.CONSTRAINT_VIOLATION
                : DefectTypes.LOGIC_MISMATCH;
    }

    /** 按中文标签反查（存量数据双读入口）；空/未知返回 null，由调用方回退 */
    public static DefectSubType fromLabel(String label) {
        if (label == null || label.isEmpty()) {
            return null;
        }
        return BY_LABEL.get(label.trim());
    }

    /** 按中文标签反查，带兜底（未知历史值统一归入业务逻辑不一致） */
    public static DefectSubType fromLabelOrDefault(String label) {
        DefectSubType t = fromLabel(label);
        return t != null ? t : LOGIC_MISMATCH;
    }

    /** 该标签是否为已知子类型（供存量兼容分支判断） */
    public static boolean isKnown(String label) {
        return fromLabel(label) != null;
    }
}
