package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 数据变更日志（2.8 整改，FR-PLAT-004）：记录关键业务实体的字段级变更前后 diff。
 * 与操作日志 AuditLog 互补：AuditLog 记录「谁在什么时间做了什么动作」，
 * 本实体进一步记录「哪个实体的哪个字段从什么值变成了什么值」，支持字段级变更追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_data_change_log")
public class DataChangeLog extends BaseEntity {
    /** 关联项目ID（无项目维度的实体如用户为空） */
    private Long projectId;
    /** 实体类型，如 user / project */
    private String entityType;
    /** 实体业务ID（字符串化，兼容多种主键） */
    private String entityId;
    /** 变更动作：create / update / delete */
    private String operation;
    /** 变更的字段名 */
    private String fieldName;
    /** 变更前的值（新增为空） */
    private String oldValue;
    /** 变更后的值（删除为空） */
    private String newValue;
    /** 操作人用户ID */
    private Long operatorId;
    /** 操作人用户名 */
    private String operatorName;
    /** 操作时间（冗余 createTime 便于查询，建表时单独列） */
    private LocalDateTime changeTime;
}
