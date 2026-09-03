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
    /** A1 判定溯源：最终结论的决策路径（RULE/NOT_REVIEWED/LLM_CONSENSUS系列/LLM_ARBITRATION系列/RULE_QUANTIFY_VETO/LLM_OWNER_OVERRIDE/LLM_SINGLE/LLM_SIM_GATE） */
    private String judgePath;
    /** A1 判定溯源明细 JSON（{"variantA":bool,"variantB":bool,"ruleRisk":x,"pairRisk":x,"selectedReason":"...","detail":"..."}），规则模式为 NULL */
    private String judgeDetail;
    /** GAP-020：缺陷子类型（不持久化，仅在内存中传递给 Defect） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String defectSubType;
}
