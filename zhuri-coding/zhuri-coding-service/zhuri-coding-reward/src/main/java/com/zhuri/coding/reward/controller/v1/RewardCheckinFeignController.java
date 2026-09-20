package com.heima.reward.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.utils.thread.AppThreadLocalUtil;
import com.heima.reward.service.CheckinService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到连续天数 Feign 接口（供其他服务远程调用，如成就勋章判定）
 * 仅限服务间内部调用，外部用户经网关访问时拒绝，防止越权读取他人签到数据。
 */
@RestController
@RequestMapping("/api/v1/reward")
public class RewardCheckinFeignController {

    @Autowired
    private CheckinService checkinService;

    /** 获取用户连续签到天数（含今日） */
    @GetMapping("/user/{userId}/checkin/continuous")
    public ResponseResult getContinuousCheckinDays(@PathVariable("userId") Long userId) {
        // 拦截器已依据 accToken 向线程注入用户：外部用户调用即拒绝，仅放行服务间 Feign 直连
        if (AppThreadLocalUtil.getUser() != null) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        return checkinService.getContinuousCheckinDays(userId);
    }
}
