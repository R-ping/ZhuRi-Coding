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

    @Override
    public ResponseResult getVirtualAssetHold(Long userId, String itemCode) {
        log.error("奖励服务不可用，校验虚拟道具失败，userId={}, itemCode={}", userId, itemCode);
        // 降级：视为无持有，并给一个不打折的比例（1.0）以保接口不中断
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("quantity", 0);
        result.put("discountRate", 1.0d);
        result.put("itemCode", itemCode);
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult consumeVirtualAsset(Long userId, String itemCode, int count) {
        log.error("奖励服务不可用，核销虚拟道具失败，userId={}, itemCode={}, count={}", userId, itemCode, count);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("quantity", 0);
        return ResponseResult.errorResult(500, "奖励服务不可用，虚拟道具核销失败");
    }
}
