package com.heima.reward.feign;

import com.heima.apis.reward.IRewardClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.UserAssets;
import com.heima.reward.mapper.UserAssetsMapper;
import com.heima.reward.service.CheckinService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

public class RewardClient implements IRewardClient {
    @Autowired
    private UserAssetsMapper userAssetsMapper;
    @Autowired
    private CheckinService checkinService;

    /**
     * 获取用户资产（矿石余额）
     * 供其他服务 Feign 调用
     */
    @GetMapping("/user/{userId}/assets")
    public ResponseResult getUserAssets(@PathVariable("userId") Long userId) {
        UserAssets assets = userAssetsMapper.selectById(userId);
        Map<String, Object> result = new HashMap<>();
        if (assets != null) {
            result.put("oreBalance", assets.getOreBalance() != null ? assets.getOreBalance() : 0);
            result.put("frozenOre", assets.getFrozenOre() != null ? assets.getFrozenOre() : 0);
            result.put("luckyValue", assets.getLuckyValue() != null ? assets.getLuckyValue() : 0);
        } else {
            result.put("oreBalance", 0);
            result.put("frozenOre", 0);
            result.put("luckyValue", 0);
        }
        return ResponseResult.okResult(result);
    }

    /**
     * 仅获取用户矿石余额（轻量接口）
     */
    @GetMapping("/user/{userId}/ore")
    public ResponseResult getUserOreBalance(@PathVariable("userId") Long userId) {
        UserAssets assets = userAssetsMapper.selectById(userId);
        int oreBalance = (assets != null && assets.getOreBalance() != null) ? assets.getOreBalance() : 0;
        Map<String, Object> result = new HashMap<>();
        result.put("oreBalance", oreBalance);
        return ResponseResult.okResult(result);
    }

    /**
     * 增加用户矿石余额（用于等级奖励等场景）
     * 供其他服务 Feign 调用
     */
    @PostMapping("/user/{userId}/ore/add")
    public ResponseResult addOreBalance(@PathVariable("userId") Long userId,
        @RequestParam("amount") int amount) {
        if (amount <= 0) {
            return ResponseResult.errorResult(400, "增加数量必须大于0");
        }
        userAssetsMapper.addOreBalance(userId, amount);
        UserAssets assets = userAssetsMapper.selectById(userId);
        int newBalance = (assets != null && assets.getOreBalance() != null) ? assets.getOreBalance() : amount;
        Map<String, Object> result = new HashMap<>();
        result.put("oreBalance", newBalance);
        result.put("added", amount);
        return ResponseResult.okResult(result);
    }

    /**
     * 获取用户连续签到天数（含今日，供其他服务 Feign 调用，成就勋章判定用）
     */
    @GetMapping("/user/{userId}/checkin/continuous")
    @Override
    public ResponseResult getContinuousCheckinDays(@PathVariable("userId") Long userId) {
        return checkinService.getContinuousCheckinDays(userId);
    }
}
