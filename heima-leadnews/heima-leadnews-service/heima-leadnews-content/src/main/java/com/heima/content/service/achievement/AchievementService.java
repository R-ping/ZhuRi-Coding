package com.heima.content.service.achievement;

import com.heima.model.achievement.vos.AchievementDataVO;

/**
 * 成就勋章判定服务 — 实时统计各维度数据与 ap_achievement 定义比对得出解锁状态
 */
public interface AchievementService {

    /**
     * 获取用户成就勋章（11 枚静态勋章 + 2 枚等级徽章）
     *
     * @param userId 目标用户ID
     * @return 勋章数据（已解锁数/总数/列表/等级徽章）
     */
    AchievementDataVO getUserAchievements(Long userId);
}
