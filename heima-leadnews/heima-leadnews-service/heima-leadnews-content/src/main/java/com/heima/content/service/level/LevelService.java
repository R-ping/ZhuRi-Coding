package com.heima.content.service.level;

import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.level.pojos.ApUserLevel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface LevelService {

    ApUserLevel getUserLevel(Long userId);

    void recordAction(Long userId, String actionType, String actionDetail);

    /**
     * 支付行为：按实际支付金额加逐日经验（金额即经验值，支持小数），受每日上限控制
     *
     * @param userId       付款用户ID
     * @param actionType   支付行为类型（如 purchase_course / reward_article）
     * @param amount       实际支付金额
     * @param actionDetail 行为详情
     * @return success / message / score（本次实际获得经验值）
     */
    Map<String, Object> recordPaymentAction(Long userId, String actionType, BigDecimal amount,
        String actionDetail);

    void calculatePower(Long userId, Long articleId, String changeType, Integer powerChange);

    Map<String, Object> calculatePowerWithLimit(Long userId, Long articleId, String changeType, Integer powerChange);

    boolean hasPermission(Long userId, String permissionCode);

    List<String> getUserPermissions(Long userId);

    Map<String, Object> getUserLevelInfo(Long userId);

    Map<String, Object> getUserLevelData(Long userId);

    Map<String, Object> recordActionWithLimit(Long userId, String actionType, String actionDetail);

    /**
     * 回退一次已累计的用户主动行为（如取消点赞/收藏/关注）：扣减今日进度与当日逐日分。
     * 用于"允许重复点赞、取消时一并回退进度"的切换式互动语义，防止反复操作刷分。
     *
     * @param userId       操作用户ID
     * @param actionType   行为类型（如 like_article）
     * @param actionDetail 行为详情（一般带"取消"标识）
     * @return success / message / score（本次回退的经验值）
     */
    Map<String, Object> rollbackActionWithLimit(Long userId, String actionType, String actionDetail);

    Map<String, Object> getTodayTaskProgress(Long userId);

    List<ApLevelConfig> getLevelConfigs(Integer levelType);

    Map<String, Object> getLevelPrivileges(Long userId);

    Map<String, Object> getUserInfoPack(Long userId);

    void assignBasicPermissions(Long userId);

    Map<String, Object> getCreatorLevelPrivileges();

    Map<String, Object> getGrowthTasks();

    Map<String, Object> getPowerDetail(Long userId);

    Map<String, Object> getUserBenefits(Long userId);
}