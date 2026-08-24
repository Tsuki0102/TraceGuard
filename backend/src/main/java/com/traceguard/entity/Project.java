package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_project")
public class Project extends BaseEntity {
    private String projectName;
    private String industryType;
    private String techStack;
    private String description;
    private Long createUserId;
    private String status;
    private Integer requirementCount;
    private Double coverageRate;
    private Integer defectCount;
    private String requirementFilePath;
    private String codeProjectPath;
    private String parseFailures;
}
