package com.zhuri.coding.model.achievement.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 等级徽章展示结构（逐友等级 / 逐力值等级，动态展示当前等级）
 */
@Data
public class AchievementLevelVO implements Serializable {

    /** 等级类型：daily=逐友等级，power=逐力值等级 */
    private String type = "";

    /** 等级名称（如 逐友等级） */
    private String name = "";

    /** 当前等级值 */
    private Integer level = 1;

    /** 等级名（如 见习掘友） */
    private String levelTitle = "";
}
