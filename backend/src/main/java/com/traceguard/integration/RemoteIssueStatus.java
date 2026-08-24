package com.traceguard.integration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GAP-009 入站同步：远程issue的当前状态查询结果。
 * - rawStatus：平台原生状态名（如 Jira 的 "Done"、禅道的 "closed"）
 * - status：映射后的统一状态枚举（IN_PROGRESS / RESOLVED / IGNORED / OPEN）
 * - description：可读描述
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteIssueStatus {
    /**
     * 平台原生状态名（Jira/禅道原始返回值）
     */
    private String rawStatus;
    /**
     * 映射后的统一状态（IN_PROGRESS / RESOLVED / IGNORED / OPEN），用于回写本地缺陷
     */
    private String status;
    /**
     * 可读描述
     */
    private String description;
}
