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

    Map<String, Object> checkIn(Long userId);

    Map<String, Object> recordActionWithLimit(Long userId, String actionType, String actionDetail);

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