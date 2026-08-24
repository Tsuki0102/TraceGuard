package com.traceguard.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GAP-020：缺陷类型统一为 FR-CHECK-003 的 4 类主类型口径。
 * 原细分类型降级为子类型（subType）保留，支撑二级筛选与详情展示。
 */
public final class DefectTypes {

    // 4 类主类型常量（对外口径）
    public static final String MISSING_REQUIREMENT  = "需求缺失";
    public static final String OVER_IMPLEMENTATION  = "代码超范围实现";
    public static final String LOGIC_MISMATCH       = "业务逻辑不一致";
    public static final String CONSTRAINT_VIOLATION  = "约束条件不满足";

    /** 主类型列表（有序） */
    public static final String[] MAIN_TYPES = {
            MISSING_REQUIREMENT, OVER_IMPLEMENTATION, LOGIC_MISMATCH, CONSTRAINT_VIOLATION
    };

    /**
     * 子类型 -> 主类型映射表。
     * 包含原 determineDefectType 产出的所有细分类型以及 generateDefects 中直接使用的类型。
     */
    private static final Map<String, String> SUB_TO_MAIN = new LinkedHashMap<>();
    static {
        // generateDefects 直接使用的类型
        SUB_TO_MAIN.put("需求缺失", MISSING_REQUIREMENT);
        SUB_TO_MAIN.put("代码超范围实现", OVER_IMPLEMENTATION);
        // determineDefectType 原细分类型
        SUB_TO_MAIN.put("缺失实现", MISSING_REQUIREMENT);
        SUB_TO_MAIN.put("超范围实现", OVER_IMPLEMENTATION);
        SUB_TO_MAIN.put("冗余实现", OVER_IMPLEMENTATION);
        SUB_TO_MAIN.put("逻辑偏离", LOGIC_MISMATCH);
        SUB_TO_MAIN.put("数据一致性风险", LOGIC_MISMATCH);
        SUB_TO_MAIN.put("安全风险", LOGIC_MISMATCH);
        SUB_TO_MAIN.put("异常处理缺失", CONSTRAINT_VIOLATION);
        SUB_TO_MAIN.put("资源管理缺失", CONSTRAINT_VIOLATION);
        SUB_TO_MAIN.put("不变量不满足", CONSTRAINT_VIOLATION);
        // GAP-046 修复：determineDefectType 直接产出的主类型子类型须保持主类型一致，
        // 否则 toMainType 回退为「业务逻辑不一致」，导致约束类缺陷分类错误
        SUB_TO_MAIN.put("约束条件不满足", CONSTRAINT_VIOLATION);
        // 需求代码不匹配：按分项得分动态归入（见 ConsistencyChecker）
        SUB_TO_MAIN.put("需求代码不匹配", LOGIC_MISMATCH);
    }

    private DefectTypes() {}

    /**
     * 将原细分类型归并为主类型。
     * @param subType 原细分类型字符串
     * @param invariantScore 不变量满足度（用于"需求代码不匹配"的动态归入）
     * @param constraintScore 约束匹配度（用于"需求代码不匹配"的动态归入）
     * @return 主类型字符串（始终为 4 类之一）
     */
    public static String toMainType(String subType, double invariantScore, double constraintScore) {
        if (subType == null || subType.isEmpty()) {
            return "";
        }
        // 已经是主类型且不在子类型表中（如"需求缺失""代码超范围实现"），直接返回
        // "需求代码不匹配"需按分项得分动态归入
        if ("需求代码不匹配".equals(subType)) {
            return invariantScore < constraintScore ? CONSTRAINT_VIOLATION : LOGIC_MISMATCH;
        }
        String main = SUB_TO_MAIN.get(subType);
        return main != null ? main : LOGIC_MISMATCH; // 未命中的子类型默认归入"业务逻辑不一致"
    }

    /**
     * 简化版：无需分项得分（适用于 generateDefects 中直接使用的已知主类型）。
     */
    public static String toMainType(String subType) {
        return toMainType(subType, 0, 0);
    }
}
