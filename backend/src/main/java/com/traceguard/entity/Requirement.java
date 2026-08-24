package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_requirement")
public class Requirement extends BaseEntity {
    private Long projectId;
    private String requirementId;
    private String originalText;
    /** GAP-019：需求标题（首行/首句截断提取，提取失败回退原文首句） */
    private String title;
    /** GAP-019：需求优先级（must/important/optional/normal，关键词映射） */
    private String priority;
    /** GAP-019：来源文档文件名（多文档批量解析） */
    private String sourceFile;
    private String requirementType;
    private String stateSet;
    private String initialState;
    private String stateTransitions;
    private String atomicConstraints;
    private String invariants;
    private String constraintRules;
    private String ambiguityReport;
    /** GAP-004：语义向量 JSON（{"vector":[...],"dim":N,"terms":"..."}；旧数据无 vector 键） */
    private String semanticVector;
    private Integer status;
    /** GAP-009：第三方平台issue标识（Jira ISSUE-KEY / 禅道 story ID），未导出为NULL */
    private String remoteIssueKey;
    // GAP-045：Kripke 语义模型补全——原子命题(AP)与状态标签函数(L)
    // AP：从需求约束点/边界条件提取的最小可判真伪命题集合（如"密码长度≥8""必须记录日志"）。
    // L：状态标签函数——本需求(视为一个状态/场景)在 L 下被赋予的（为真）原子命题集合。
    private String atomicPropositions; // 原子命题集合，逗号分隔的可判真伪命题
    private String stateLabeling; // 状态标签函数 L(state)={为真的原子命题}，JSON 列表
}
