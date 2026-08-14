package com.heima.apis.reward.fallback;

import com.heima.apis.reward.IRewardClient;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class IRewardClientFallback implements IRewardClient {

    @Override
    public ResponseResult getUserAssets(Long userId) {
        log.error("奖励服务不可用，获取用户资产失败，userId={}", userId);
        return ResponseResult.okResult(new java.util.HashMap<String, Object>() {{
            put("oreBalance", 0);
            put("frozenOre", 0);
            put("luckyValue", 0);
        }});
    }

    @Override
    public ResponseResult getUserOreBalance(Long userId) {
        log.error("奖励服务不可用，获取用户矿石余额失败，userId={}", userId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("oreBalance", 0);
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult addOreBalance(Long userId, int amount) {
        log.error("奖励服务不可用，增加用户矿石余额失败，userId={}, amount={}", userId, amount);
        return ResponseResult.errorResult(500, "奖励服务不可用，矿石奖励发放失败");
    }

    @Override
    public ResponseResult getContinuousCheckinDays(Long userId) {
        log.error("奖励服务不可用，获取连续签到天数失败，userId={}", userId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("continuousDays", 0);
        return ResponseResult.okResult(result);
    }
}
