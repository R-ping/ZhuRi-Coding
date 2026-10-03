package com.zhuri.coding.reward.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import com.zhuri.coding.reward.service.CheckinService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 幂等完成今日打卡（供"每日一题"答对后复用签到体系的连续记录）。
     *
     * <p>语义：尽力打卡——首次调用执行签到（发放签到奖励），已签到/短时限流（429）不视为错误；
     * 无论哪条路径，最终都回读一次连续状态返回，口径与签到页展示完全一致
     * （连续天数只保留这一份记录，避免两套打卡数据）。</p>
     */
    @PostMapping("/user/{userId}/checkin/complete")
    public ResponseResult completeCheckin(@PathVariable("userId") Long userId) {
        if (AppThreadLocalUtil.getUser() != null) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        ResponseResult done = checkinService.doCheckin(userId);
        if (done != null && done.getCode() != null && done.getCode() != 200
                && done.getCode() != 400 && done.getCode() != 429) {
            // 非"已签到/限流"的异常（如内部错误）原样上抛，调用方记录并 fail-open
            return done;
        }
        return checkinService.getContinuousCheckinDays(userId);
    }
}
