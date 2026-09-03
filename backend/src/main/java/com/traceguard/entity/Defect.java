package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_defect")
public class Defect extends BaseEntity {
    private Long taskId;
    private Long projectId;
    private Long consistencyResultId;
    private Long requirementId;
    private Long codeUnitId;
    private String defectId;
    private String defectLevel;
    private String defectType;
    private String subType;
    private String defectReason;
    private String repairSuggestion;
    private String codeSnippet;
    private String requirementText;
    /** 2.6 整改：缺陷命中的具体代码行号（相对整个源文件的绝对行号，基于 CodeUnit.startLine 偏移），未定位为 null */
    private Integer defectLine;
    /** GAP-011：缺陷状态（pending/processing/resolved/ignored） */
    private String status;
    /** GAP-011：最近状态流转时间 */
    private LocalDateTime handleTime;
    /** GAP-011：最近流转操作人用户ID */
    private Long handlerId;
    /** GAP-009：第三方平台issue标识（Jira ISSUE-KEY / 禅道 bug ID），未推送为NULL */
    private String remoteIssueKey;
    /**
     * P1-4：规则信号分解 JSON（explainSignals 结果：{"risk":总风险,"signals":{"numericMismatch":0.21,...}}），
     * 供 Defects.vue 展示"命中规则 + 各信号贡献"；空/为 NULL 表示无规则证据（规则链路未命中）。
     */
    private String riskSignals;
    /** A1 判定溯源：最终结论的决策路径（继承自关联 ConsistencyResult.judgePath），规则模式为 NULL */
    private String judgePath;
    /** A1 判定溯源明细 JSON（继承自关联 ConsistencyResult.judgeDetail），规则模式为 NULL */
    private String judgeDetail;
}
