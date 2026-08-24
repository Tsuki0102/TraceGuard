package com.traceguard.integration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GAP-009：远程状态（与本地DefectStatus映射）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteStatus {
    /**
     * IN_PROGRESS / RESOLVED / IGNORED
     */
    private String status;
    /**
     * 状态描述
     */
    private String description;
}