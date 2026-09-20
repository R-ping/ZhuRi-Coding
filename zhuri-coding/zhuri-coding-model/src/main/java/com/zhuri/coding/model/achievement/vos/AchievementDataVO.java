package com.heima.model.achievement.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 成就接口整体返回结构
 */
@Data
public class AchievementDataVO implements Serializable {

    /** 已解锁静态勋章数 */
    private Integer unlockedCount = 0;

    /** 勋章总数（静态勋章 + 等级徽章） */
    private Integer totalCount = 0;

    /** 静态勋章列表（11 枚） */
    private List<AchievementItemVO> list = new ArrayList<>();

    /** 等级徽章列表（2 枚） */
    private List<AchievementLevelVO> levels = new ArrayList<>();
}
