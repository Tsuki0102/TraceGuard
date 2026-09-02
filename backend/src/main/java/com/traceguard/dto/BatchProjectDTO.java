package com.traceguard.dto;

import lombok.Data;

import java.util.List;

/**
 * 项目批量操作入参（个性化增强 BATCH-4）
 * action ∈ delete（进回收站）/ archive（归档）/ restore（恢复归档）
 */
@Data
public class BatchProjectDTO {

    private String action;

    private List<Long> ids;
}
