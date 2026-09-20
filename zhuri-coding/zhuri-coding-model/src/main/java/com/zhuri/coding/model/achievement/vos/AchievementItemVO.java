package com.zhuri.coding.model.achievement.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 单枚静态勋章展示结构（字段值严禁为 null，遵循全局序列化规范）
 */
@Data
public class AchievementItemVO implements Serializable {

    /** 勋章唯一编码 */
    private String code = "";

    /** 勋章名称 */
    private String name = "";

    /** 分类：1=新人成长，2=活跃成就 */
    private Integer category = 2;

    /** 图标（emoji 字符） */
    private String icon = "";

    /** 解锁条件文案 */
    private String description = "";

    /** 是否已解锁 */
    private Boolean unlocked = false;

    /** 当前进度值 */
    private Long progress = 0L;

    /** 解锁阈值 */
    private Integer threshold = 0;
}
