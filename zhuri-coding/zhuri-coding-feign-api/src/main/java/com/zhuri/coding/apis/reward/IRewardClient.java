package com.heima.apis.reward;

import com.heima.apis.reward.fallback.IRewardClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(value = "leadnews-reward", fallback = IRewardClientFallback.class)
public interface IRewardClient {

    /**
     * 获取用户资产信息（矿石余额、冻结矿石、幸运值）
     */
    @GetMapping("/api/v1/reward/user/{userId}/assets")
    ResponseResult getUserAssets(@PathVariable("userId") Long userId);

    /**
     * 获取用户矿石余额（轻量接口）
     */
    @GetMapping("/api/v1/reward/user/{userId}/ore")
    ResponseResult getUserOreBalance(@PathVariable("userId") Long userId);

    /**
     * 增加用户矿石余额（用于等级奖励等场景）
     */
    @PostMapping("/api/v1/reward/user/{userId}/ore/add")
    ResponseResult addOreBalance(@PathVariable("userId") Long userId,
                                 @RequestParam("amount") int amount);

    /**
     * 获取用户连续签到天数（成就勋章-连续签到30天判定用）
     */
    @GetMapping("/api/v1/reward/user/{userId}/checkin/continuous")
    ResponseResult getContinuousCheckinDays(@PathVariable("userId") Long userId);

    /**
     * 校验用户是否持有指定虚拟道具（5折券等），并返回折扣比例（课程下单前调用）
     */
    @GetMapping("/api/v1/reward/user/{userId}/virtual-asset/hold")
    ResponseResult getVirtualAssetHold(@PathVariable("userId") Long userId,
                                       @RequestParam("itemCode") String itemCode);

    /**
     * 核销用户虚拟道具（课程支付成功后调用，原子扣减，防止超核）
     */
    @PostMapping("/api/v1/reward/user/{userId}/virtual-asset/consume")
    ResponseResult consumeVirtualAsset(@PathVariable("userId") Long userId,
                                       @RequestParam("itemCode") String itemCode,
                                       @RequestParam(value = "count", defaultValue = "1") int count);
}
