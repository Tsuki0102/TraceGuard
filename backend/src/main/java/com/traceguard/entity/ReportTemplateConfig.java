package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * GAP-010：报告模板配置（FR-CHECK-005 业务规则 3）
 * 模板 = 章节有序列表（sections JSON），持久化到 tg_report_template_config
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_report_template_config")
public class ReportTemplateConfig extends BaseEntity {
    /** 模板编码（系统模板：FULL/DEFECT_ONLY/BRIEF；用户模板生成 UUID 短码） */
    private String code;
    /** 模板名称 */
    private String templateName;
    /** 章节配置 JSON([{key,title},...]，数组顺序即渲染顺序) */
    private String sections;
    /** 报告主标题（空=系统默认标题） */
    private String title;
    /** 封面副标题（4.4 整改：报告排版自定义，空=不显示） */
    private String subtitle;
    /** 页眉文本（4.4 整改：报告排版自定义，空=使用系统默认页眉） */
    private String headerText;
    /** 前端展示排序 */
    private Integer sort;
    /** 是否默认模板（全局唯一） */
    private Boolean isDefault;
    /** 系统内置模板（不可删改） */
    private Boolean isSystem;
    /** 创建人用户ID（系统模板为 NULL） */
    private Long userId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
