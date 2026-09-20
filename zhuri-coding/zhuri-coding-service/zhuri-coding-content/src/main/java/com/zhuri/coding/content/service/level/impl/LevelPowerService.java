package com.heima.content.service.level.impl;

import static com.heima.content.constants.LevelScoreConstants.POWER_ACTION_LIMIT;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.user.ApUserDailyLogMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.user.pojos.ApUserDailyLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 逐力值计算服务 — 负责文章逐力值计算、记录和等级更新
 */
@Slf4j
@Service
public class LevelPowerService {

    @Autowired
    private ApUserDailyLogMapper dailyLogMapper;

    @Autowired
    private ApUserLevelMapper userLevelMapper;

    @Autowired
    private LevelQueryService levelQueryService;

    @Autowired
    private LevelPermissionService permissionService;

    @Autowired
    private LevelDiamondService diamondService;

    /**
     * 计算逐力值（含限制校验）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> calculatePowerWithLimit(Long userId, Long articleId, String changeType,
        Integer powerChange) {
        Map<String, Object> result = new HashMap<>();

        int actualPower = calculateActualPower(userId, articleId, changeType, powerChange);
        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
        if (actualPower <= 0) {
            result.put("success", false);
            result.put("message", "未获得逐力值");
            result.put("power", 0);
            result.put("powerValue", userLevel.getPowerValue());
            result.put("powerLevel", userLevel.getPowerLevel());
            return result;
        }

        ApUserDailyLog dailyLog = new ApUserDailyLog();
        dailyLog.setUserId(userId);
        dailyLog.setPowerChange(actualPower);
        dailyLog.setChangeType(changeType);
        dailyLog.setSourceId(articleId);
        dailyLog.setCalculatedAt(new java.sql.Date(System.currentTimeMillis()));
        dailyLogMapper.insert(dailyLog);

        userLevel.setPowerValue(userLevel.getPowerValue() + actualPower);
        userLevel.setPowerValueToday(userLevel.getPowerValueToday() + actualPower);

        int newPowerLevel = levelQueryService.calculateLevel(2,
            BigDecimal.valueOf(userLevel.getPowerValue() != null ? userLevel.getPowerValue() : 0));
        int oldLevel = userLevel.getPowerLevel();
        boolean levelChanged = false;
        if (newPowerLevel != userLevel.getPowerLevel()) {
            userLevel.setPowerLevel(newPowerLevel);
            permissionService.updateUserPermissions(userId, 2, oldLevel, newPowerLevel);
            levelChanged = true;
            diamondService.grantDiamondOnLevelUp(userId, 2, newPowerLevel);
        }

        userLevelMapper.updateById(userLevel);

        log.info("用户{}获得逐力值{}，当前逐力等级{}", userId, actualPower, userLevel.getPowerLevel());

        result.put("success", true);
        result.put("message", "逐力值计算成功");
        result.put("power", actualPower);
        result.put("powerValue", userLevel.getPowerValue());
        result.put("powerLevel", userLevel.getPowerLevel());
        result.put("levelChanged", levelChanged);
        result.put("oldLevel", oldLevel);
        result.put("newLevel", newPowerLevel);

        return result;
    }

    /**
     * 计算逐力值（简化版，不返回结果）
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculatePower(Long userId, Long articleId, String changeType, Integer powerChange) {
        calculatePowerWithLimit(userId, articleId, changeType, powerChange);
    }

    private int calculateActualPower(Long userId, Long articleId, String changeType, Integer powerChange) {
        String today = new java.sql.Date(System.currentTimeMillis()).toString();

        Integer dailyLimit = POWER_ACTION_LIMIT.get(changeType);
        if (dailyLimit != null) {
            // 幂等防护：同一来源(sourceId)当日已发放过则该来源不再重复发放。
            // 用于兜底审核责任链阶段重试时，避免同一篇文章重复叠加逐力值。
            LambdaQueryWrapper<ApUserDailyLog> duplicateQuery = new LambdaQueryWrapper<>();
            duplicateQuery.eq(ApUserDailyLog::getUserId, userId);
            duplicateQuery.eq(ApUserDailyLog::getChangeType, changeType);
            duplicateQuery.eq(ApUserDailyLog::getSourceId, articleId);
            duplicateQuery.apply("DATE(calculated_at) = {0}", today);
            if (dailyLogMapper.selectCount(duplicateQuery) > 0) {
                log.info("同一来源当日已发放逐力值，跳过重复发放 userId={}, changeType={}, articleId={}",
                    userId, changeType, articleId);
                return 0;
            }

            LambdaQueryWrapper<ApUserDailyLog> limitQuery = new LambdaQueryWrapper<>();
            limitQuery.eq(ApUserDailyLog::getUserId, userId);
            limitQuery.eq(ApUserDailyLog::getChangeType, changeType);
            limitQuery.apply("DATE(calculated_at) = {0}", today);
            long todayCount = dailyLogMapper.selectCount(limitQuery);
            if (todayCount >= dailyLimit) {
                return 0;
            }
        }

        return switch (changeType) {
            case "publish_article" -> 10;
            case "get_like", "get_comment", "get_favorite" -> 1;
            case "get_read" -> powerChange / 100;
            default -> powerChange;
        };
    }
}