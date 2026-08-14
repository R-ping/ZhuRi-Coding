package com.heima.reward.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.service.CheckinService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到连续天数 Feign 接口（供其他服务远程调用，如成就勋章判定）
 */
@RestController
@RequestMapping("/api/v1/reward")
public class RewardCheckinFeignController {

    @Autowired
    private CheckinService checkinService;

    /** 获取用户连续签到天数（含今日） */
    @GetMapping("/user/{userId}/checkin/continuous")
    public ResponseResult getContinuousCheckinDays(@PathVariable("userId") Long userId) {
        return checkinService.getContinuousCheckinDays(userId);
    }
}
