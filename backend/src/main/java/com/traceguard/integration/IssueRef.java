package com.traceguard.integration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GAP-009：远程issue引用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRef {
    /**
     * 远程issue key（Jira ISSUE-123 / 禅道 bugID）
     */
    private String remoteKey;
    /**
     * 远程issue URL
     */
    private String remoteUrl;
    /**
     * 是否为新建操作（false表示更新）
     */
    private boolean created;
}