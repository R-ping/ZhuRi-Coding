package com.zhuri.coding.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.content.mapper.level.ApLevelConfigMapper;
import com.zhuri.coding.content.mapper.level.ApUserLevelMapper;
import com.zhuri.coding.content.service.level.LevelPermissionService;
import com.zhuri.coding.model.level.pojos.ApLevelConfig;
import com.zhuri.coding.model.level.pojos.ApUserLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户等级查询服务 — 负责等级信息查询、等级配置查询、等级计算
 */
@Slf4j
@Service
public class LevelQueryService {

    /** 等级配置本地缓存 TTL：配置变更低频，5 分钟刷新一次 */
    private static final long CONFIG_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 等级配置本地缓存：key=levelType */
    private final Map<Integer, LevelConfigCacheEntry> configCache = new ConcurrentHashMap<>();

    private static class LevelConfigCacheEntry {
        final List<ApLevelConfig> configs;
        final long expireAt;
        LevelConfigCacheEntry(List<ApLevelConfig> configs) {
            this.configs = configs;
            this.expireAt = System.currentTimeMillis() + CONFIG_CACHE_TTL_MS;
        }
    }

    @Autowired
    private ApUserLevelMapper userLevelMapper;

    @Autowired
    private ApLevelConfigMapper levelConfigMapper;

    @Autowired
    private LevelPermissionService permissionService;

    @Autowired
    private IRewardClient rewardClient;

    /**
     * 获取指定类型的等级配置（带本地缓存，避免高频计算等级时反复查库）
     */
    private List<ApLevelConfig> getCachedConfigs(Integer levelType) {
        LevelConfigCacheEntry entry = configCache.get(levelType);
        if (entry == null || entry.expireAt < System.currentTimeMillis()) {
            entry = new LevelConfigCacheEntry(loadConfigs(levelType));
            configCache.put(levelType, entry);
        }
        return entry.configs;
    }

    private List<ApLevelConfig> loadConfigs(Integer levelType) {
        LambdaQueryWrapper<ApLevelConfig> query = new LambdaQueryWrapper<>();
        query.eq(ApLevelConfig::getLevelType, levelType);
        query.orderByAsc(ApLevelConfig::getLevelValue);
        return levelConfigMapper.selectList(query);
    }

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
     * 根据积分计算等级（内存计算，配置走本地缓存）
     */
    public int calculateLevel(int levelType, BigDecimal score) {
        List<ApLevelConfig> configs = getCachedConfigs(levelType);
        if (configs.isEmpty()) {
            return 1;
        }
        // 命中 min_score <= score 的最高档
        ApLevelConfig hit = null;
        for (ApLevelConfig config : configs) {
            if (config.getMinScore() != null
                    && BigDecimal.valueOf(config.getMinScore()).compareTo(score) <= 0) {
                if (hit == null || config.getMinScore() > hit.getMinScore()) {
                    hit = config;
                }
            }
        }
        if (hit != null) {
            return hit.getLevelValue();
        }
        // 无匹配范围时返回最高等级
        return configs.get(configs.size() - 1).getLevelValue();
    }
}