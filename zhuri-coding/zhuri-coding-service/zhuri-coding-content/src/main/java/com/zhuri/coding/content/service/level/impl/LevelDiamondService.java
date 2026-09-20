package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.reward.IRewardClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.content.mapper.level.ApLevelConfigMapper;
import com.heima.content.mapper.user.ApUserDiamondLogMapper;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.user.pojos.ApUserDiamondLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 钻石奖励服务 — 负责等级升级时发放矿石奖励
 * 矿石余额统一由reward服务管理，本服务通过Feign远程调用操作
 */
@Slf4j
@Service
public class LevelDiamondService {

    @Autowired
    private ApLevelConfigMapper levelConfigMapper;

    @Autowired
    private ApUserDiamondLogMapper diamondLogMapper;

    @Autowired
    private LevelQueryService levelQueryService;

    @Autowired
    private IRewardClient rewardClient;

    /**
     * 等级升级时发放矿石奖励
     */
    @Transactional(rollbackFor = Exception.class)
    public void grantDiamondOnLevelUp(Long userId, int levelType, int newLevel) {
        try {
            LambdaQueryWrapper<ApLevelConfig> configQuery = new LambdaQueryWrapper<>();
            configQuery.eq(ApLevelConfig::getLevelType, levelType);
            configQuery.eq(ApLevelConfig::getLevelValue, newLevel);
            ApLevelConfig config = levelConfigMapper.selectOne(configQuery);

            if (config == null || config.getDiamondReward() == null || config.getDiamondReward() <= 0) {
                log.info("等级{}无矿石奖励配置, levelType={}, newLevel={}", newLevel, levelType, newLevel);
                return;
            }

            int diamondAmount = config.getDiamondReward();

            int newBalance = 0;
            try {
                ResponseResult addResult = rewardClient.addOreBalance(userId, diamondAmount);
                if (addResult != null && addResult.getCode() == 200 && addResult.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> oreData = (Map<String, Object>) addResult.getData();
                    Object balanceVal = oreData.get("oreBalance");
                    if (balanceVal instanceof Number) {
                        newBalance = ((Number) balanceVal).intValue();
                    }
                } else {
                    log.warn("增加矿石余额失败，跳过奖励发放, userId={}, amount={}", userId, diamondAmount);
                    return;
                }
            } catch (Exception e) {
                log.error("调用奖励服务增加矿石余额异常, userId={}, amount={}", userId, diamondAmount, e);
                return;
            }

            ApUserDiamondLog diamondLog = new ApUserDiamondLog();
            diamondLog.setUserId(userId);
            diamondLog.setChangeType("level_up");
            diamondLog.setChangeAmount(diamondAmount);
            diamondLog.setBalance(newBalance);
            diamondLog.setSourceId(String.valueOf(newLevel));
            diamondLog.setCreatedAt(java.time.LocalDateTime.now());
            diamondLogMapper.insert(diamondLog);

            log.info("等级升级矿石奖励发放, userId={}, levelType={}, newLevel={}, diamondAmount={}, newBalance={}",
                userId, levelType, newLevel, diamondAmount, newBalance);
        } catch (Exception e) {
            log.error("矿石奖励发放异常, userId={}, levelType={}, newLevel={}", userId, levelType, newLevel, e);
        }
    }
}