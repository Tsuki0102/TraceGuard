package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_code_unit")
public class CodeUnit extends BaseEntity {
    private Long projectId;
    private String codeId;
    private String filePath;
    private String className;
    private String methodName;
    private Integer startLine;
    private Integer endLine;
    private String codeContent;
    private String logicDescription;
    private String logicAnalysis; // GAP-006：CodeLogicDescriber 结构化分析结果（JSON）
    /** GAP-016：方法圈复杂度（McCabe，基值 1，若/循环/switch-case/catch/&&/||/?: 各 +1） */
    private Integer cyclomaticComplexity;
    private String semanticVector;
    private String cfgData;
    private String constraints;
    /** P2-5：源文件内容 sha256 摘要（增量分析：文件级变更判定，每文件内各方法单元一致） */
    private String contentHash;
}
