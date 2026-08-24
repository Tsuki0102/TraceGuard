package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_formal_specification")
public class FormalSpecification extends BaseEntity {
    private Long projectId;
    private Long requirementId;
    private String specId;
    private String alloyCode;
    private String verificationStatus;
    private String verificationResult;
    private String optimizationSuggestion;
    /** GAP-002：Alloy 真实求解结果 JSON（engine/satStatus/instanceCount/counterexample/message/elapsedMs） */
    private String verificationDetail;
}
