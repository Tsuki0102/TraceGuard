package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_code_defect")
public class CodeDefect extends BaseEntity {
    private Long projectId;
    private Long taskId;
    private String filePath;
    private String className;
    private String methodName;
    private Integer lineNumber;
    private String defectType;
    private String severity;
    private String description;
    private String repairSuggestion;
    private String codeSnippet;
}
