package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户界面偏好（个性化增强 BATCH-4）
 * 每用户一行，prefJson 存整包偏好（主题/密度/工作台卡片布局），前端驱动字段自由扩展。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tg_user_preference")
public class UserPreference extends BaseEntity {

    /** 用户 ID（唯一） */
    private Long userId;

    /** 偏好 JSON 文本：{theme, accent, density, dashCards} */
    private String prefJson;
}
