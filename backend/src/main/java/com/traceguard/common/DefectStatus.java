package com.traceguard.common;

import java.util.*;

/**
 * GAP-011：缺陷状态枚举与合法流转表
 * 四状态：pending（待处理）/ processing（处理中）/ resolved（已解决）/ ignored（已忽略）
 */
public enum DefectStatus {
    PENDING("pending", "待处理"),
    PROCESSING("processing", "处理中"),
    RESOLVED("resolved", "已解决"),
    IGNORED("ignored", "已忽略");

    private final String code;
    private final String label;

    DefectStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }

    /** 合法流转矩阵 */
    private static final Map<String, Set<String>> TRANSITIONS = new HashMap<>();
    static {
        TRANSITIONS.put("pending", Set.of("processing", "ignored"));
        TRANSITIONS.put("processing", Set.of("resolved", "ignored", "pending"));
        TRANSITIONS.put("resolved", Set.of("processing"));
        TRANSITIONS.put("ignored", Set.of("processing"));
    }

    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) return false;
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** 获取当前状态可流转的目标状态列表（用于错误提示） */
    public static List<String> allowedTargets(String from) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null ? new ArrayList<>(allowed) : Collections.emptyList();
    }

    public static String labelOf(String code) {
        if (code == null) return PENDING.label;
        for (DefectStatus s : values()) {
            if (s.code.equals(code)) return s.label;
        }
        return PENDING.label;
    }

    public static boolean isValid(String code) {
        for (DefectStatus s : values()) {
            if (s.code.equals(code)) return true;
        }
        return false;
    }
}
