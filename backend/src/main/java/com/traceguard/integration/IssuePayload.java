package com.traceguard.integration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GAP-009：Issue推送载荷
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssuePayload {
    /**
     * 类型：STORY(需求) 或 BUG(缺陷)
     */
    private IssueType type;
    /**
     * 标题
     */
    private String title;
    /**
     * 描述（纯文本）
     */
    private String description;
    /**
     * 优先级：HIGHEST/HIGH/MEDIUM/LOW/LOWEST
     */
    private String priority;
    /**
     * 关联需求编码（仅缺陷需要）
     */
    private String relatedReqCode;
    /**
     * 缺陷主类型（GAP-020四类口径，仅缺陷需要）
     */
    private String defectType;
    /**
     * 缺陷子类型（仅缺陷需要）
     */
    private String subType;

    public enum IssueType {
        STORY, BUG
    }
}