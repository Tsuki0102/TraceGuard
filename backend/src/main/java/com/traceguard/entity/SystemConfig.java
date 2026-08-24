package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置表（AUD-07，GAP-002 需求解析规则可视化配置）
 * 通用键值存储：config_key 为主键，config_value 为 JSON 文本（如需求解析规则 req_parse_rules）。
 */
@Data
@TableName("sys_config")
public class SystemConfig {

    @TableId(type = IdType.INPUT)
    private String configKey;

    /** 配置值（JSON 文本，由调用方按 config_key 解析） */
    private String configValue;

    /** 配置说明（前端展示用） */
    private String description;

    /** 最近更新人用户名 */
    private String updatedBy;

    private LocalDateTime updatedAt;
}
