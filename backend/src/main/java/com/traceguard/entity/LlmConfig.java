package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 大模型运行时配置（GAP-021，单行表，id 恒为 1）
 * api_key 加密存储于 providers_json。
 */
@Data
@TableName("tg_llm_config")
public class LlmConfig {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Boolean enabled;
    private String providersJson;
    private String routingJson;
    private String modelsJson;
    private LocalDateTime updateTime;
    private Long updateBy;
}
