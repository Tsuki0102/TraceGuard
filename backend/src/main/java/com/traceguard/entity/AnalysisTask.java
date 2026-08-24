package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_analysis_task")
public class AnalysisTask extends BaseEntity {
    private Long projectId;
    private String taskName;
    private String status;
    private Integer progress;
    private String currentStep;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Double weightAlpha;
    private Double weightBeta;
    private Double weightGamma;
    private Double thresholdT1;
    private Double thresholdT2;
    private String errorMessage;
    private String executionLog;
}
