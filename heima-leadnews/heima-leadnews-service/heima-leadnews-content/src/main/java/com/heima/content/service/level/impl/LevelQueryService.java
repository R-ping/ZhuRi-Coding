package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.reward.IRewardClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.content.mapper.level.ApLevelConfigMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.level.pojos.ApUserLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户等级查询服务 — 负责等级信息查询、等级配置查询、等级计算
 */
@Slf4j
@Service
public class LevelQueryService {

    @Autowired
    private ApUserLevelMapper userLevelMapper;

    @Autowired
    private ApLevelConfigMapper levelConfigMapper;

    @Autowired
    private LevelPermissionService permissionService;

    @Autowired
    private IRewardClient rewardClient;

    /**
     * 获取用户等级信息，不存在则创建默认记录
     */
    public ApUserLevel getUserLevel(Long userId) {
        LambdaQueryWrapper<ApUserLevel> query = new LambdaQueryWrapper<>();
        query.eq(ApUserLevel::getUserId, userId);
        ApUserLevel userLevel = userLevelMapper.selectOne(query);

        if (userLevel == null) {
            userLevel = new ApUserLevel();
            userLevel.setUserId(userId);
            userLevel.setDailyScore(BigDecimal.ZERO);
            userLevel.setDailyLevel(1);
            userLevel.setPowerValue(0);
            userLevel.setPowerLevel(1);
            userLevel.setDailyScoreToday(BigDecimal.ZERO);
            userLevel.setPowerValueToday(0);
            userLevelMapper.insert(userLevel);
        }

        return userLevel;
    }

    /**
     * 获取用户等级完整信息（含等级标题、描述、权限列表）
     */
    public Map<String, Object> getUserLevelInfo(Long userId) {
        Map<String, Object> result = new HashMap<>();
        ApUserLevel userLevel = getUserLevel(userId);

        LambdaQueryWrapper<ApLevelConfig> dailyConfigQuery = new LambdaQueryWrapper<>();
        dailyConfigQuery.eq(ApLevelConfig::getLevelType, 1);
        dailyConfigQuery.eq(ApLevelConfig::getLevelValue, userLevel.getDailyLevel());
        ApLevelConfig dailyConfig = levelConfigMapper.selectOne(dailyConfigQuery);

        LambdaQueryWrapper<ApLevelConfig> powerConfigQuery = new LambdaQueryWrapper<>();
        powerConfigQuery.eq(ApLevelConfig::getLevelType, 2);
        powerConfigQuery.eq(ApLevelConfig::getLevelValue, userLevel.getPowerLevel());
        ApLevelConfig powerConfig = levelConfigMapper.selectOne(powerConfigQuery);

        result.put("dailyScore", userLevel.getDailyScore());
        result.put("dailyLevel", userLevel.getDailyLevel());
        result.put("dailyTitle", dailyConfig != null ? dailyConfig.getTitle() : "");
        result.put("dailyDescription", dailyConfig != null ? dailyConfig.getDescription() : "");

        result.put("powerValue", userLevel.getPowerValue());
        result.put("powerLevel", userLevel.getPowerLevel());
        result.put("powerTitle", powerConfig != null ? powerConfig.getTitle() : "");
        result.put("powerDescription", powerConfig != null ? powerConfig.getDescription() : "");

        result.put("permissions", permissionService.getUserPermissions(userId));

        return result;
    }

    /**
     * 获取用户等级数据（含等级徽章、经验值、升级进度、矿石数）
     * 矿石数通过Feign远程调用reward服务获取，不再从ap_user_level表读取
     */
    public Map<String, Object> getUserLevelData(Long userId) {
        Map<String, Object> result = new HashMap<>();
        ApUserLevel userLevel = getUserLevel(userId);

        Integer dailyLevel = userLevel.getDailyLevel() != null ? userLevel.getDailyLevel() : 1;
        BigDecimal dailyScore = userLevel.getDailyScore() != null ? userLevel.getDailyScore() : BigDecimal.ZERO;

        Integer diamondBalance = 0;
        try {
            ResponseResult oreResult = rewardClient.getUserOreBalance(userId);
            if (oreResult != null && oreResult.getCode() == 200 && oreResult.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> oreData = (Map<String, Object>) oreResult.getData();
                Object oreVal = oreData.get("oreBalance");
                if (oreVal instanceof Number) {
                    diamondBalance = ((Number) oreVal).intValue();
                }
            }
        } catch (Exception e) {
            log.warn("远程获取用户矿石余额失败，userId={}, error={}", userId, e.getMessage());
        }

        LambdaQueryWrapper<ApLevelConfig> nextLevelQuery = new LambdaQueryWrapper<>();
        nextLevelQuery.eq(ApLevelConfig::getLevelType, 1);
        nextLevelQuery.eq(ApLevelConfig::getLevelValue, dailyLevel + 1);
        ApLevelConfig nextLevelConfig = levelConfigMapper.selectOne(nextLevelQuery);

        int levelMax;
        if (nextLevelConfig != null && nextLevelConfig.getMinScore() != null) {
            levelMax = nextLevelConfig.getMinScore();
        } else {
            levelMax = dailyLevel * 150;
        }

        int levelPercent = levelMax > 0 ? dailyScore.multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(levelMax), 0, java.math.RoundingMode.DOWN).intValue() : 0;

        result.put("levelBadge", "ZR." + dailyLevel);
        result.put("levelScore", dailyScore);
        result.put("levelMax", levelMax);
        result.put("levelPercent", levelPercent);
        result.put("diamondCount", diamondBalance);
        result.put("dailyLevel", dailyLevel);
        result.put("dailyScore", dailyScore);

        return result;
    }

    /**
     * 获取等级配置列表
     */
    public List<ApLevelConfig> getLevelConfigs(Integer levelType) {
        LambdaQueryWrapper<ApLevelConfig> query = new LambdaQueryWrapper<>();
        query.eq(ApLevelConfig::getLevelType, levelType);
        query.orderByAsc(ApLevelConfig::getLevelValue);
        return levelConfigMapper.selectList(query);
    }

    /**
     * 根据积分计算等级
     */
    public int calculateLevel(int levelType, BigDecimal score) {
        LambdaQueryWrapper<ApLevelConfig> query = new LambdaQueryWrapper<>();
        query.eq(ApLevelConfig::getLevelType, levelType);
        query.le(ApLevelConfig::getMinScore, score);
        query.orderByDesc(ApLevelConfig::getMinScore);
        query.last("LIMIT 1");
        ApLevelConfig config = levelConfigMapper.selectOne(query);

        if (config != null) {
            return config.getLevelValue();
        }

        // 无匹配范围时返回最高等级
        query.clear();
        query.eq(ApLevelConfig::getLevelType, levelType);
        query.orderByDesc(ApLevelConfig::getLevelValue);
        query.last("LIMIT 1");
        ApLevelConfig highest = levelConfigMapper.selectOne(query);
        return highest != null ? highest.getLevelValue() : 1;
    }
}