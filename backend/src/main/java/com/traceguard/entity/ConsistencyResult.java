package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_consistency_result")
public class ConsistencyResult extends BaseEntity {
    private Long taskId;
    private Long projectId;
    private Long requirementId;
    private Long codeUnitId;
    private String matchId;
    private Double semanticSimilarity;
    private Double constraintMatchDegree;
    private Double invariantSatisfaction;
    private Double totalSimilarity;
    private String consistencyStatus;
    private String defectType;
    /** GAP-020：缺陷子类型（不持久化，仅在内存中传递给 Defect） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String defectSubType;
}
