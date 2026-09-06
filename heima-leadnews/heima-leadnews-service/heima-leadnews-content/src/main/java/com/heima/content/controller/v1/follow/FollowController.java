
package com.heima.content.controller.v1.follow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.service.fans.FansDataService;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/follow")
public class FollowController {

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    @Autowired
    private FansDataService fansDataService;

    @Autowired
    private ApFollowMapper apFollowMapper;

    /**
     * 唯一的用户关注接口（统一走行为总线 FollowBehaviorHandler）。
     * operation：0=关注，1=取消关注；默认关注。
     * 身份来源：
     * 1. 经网关的外部请求：ThreadLocal 已写入鉴权用户 → 使用认证用户，忽略入参 userId，杜绝伪造越权关注；
     * 2. 内部 Feign 直调（无网关鉴权上下文）：沿用调用方显式传入的 userId，作为内部服务契约。
     */
    @PostMapping("/do")
    public ResponseResult doFollow(@RequestParam(value = "userId", required = false) Long userId,
                                   @RequestParam("followUserId") Long followUserId,
                                   @RequestParam(value = "operation", required = false, defaultValue = "0") Integer operation) {
        ApUser authUser = AppThreadLocalUtil.getUser();
        Integer actingUserId;
        if (authUser != null && authUser.getId() != null) {
            // 网关已认证：以认证身份为准，不允许通过入参伪造他人身份关注
            actingUserId = authUser.getId();
        } else {
            // 内部 Feign 场景：调用方显式指定身份
            if (userId == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN, "缺少操作用户身份");
            }
            actingUserId = userId.intValue();
        }
        if (followUserId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "followUserId不能为空");
        }
        if (actingUserId.equals(followUserId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不能关注自己");
        }
        if (behaviorEventBus != null) {
            BehaviorContext context = new BehaviorContext(BehaviorType.FOLLOW_USER, actingUserId);
            context.withTarget(3, followUserId)
                    .withTargetUser(followUserId.intValue());
            // operation=1 取消关注 → 走总线 rollback（删除关系 + 回退进度）；否则关注 → 走总线 execute
            boolean unfollow = operation != null && operation == 1;
            return unfollow ? behaviorEventBus.rollback(context) : behaviorEventBus.execute(context);
        }
        // 兜底：总线不可用时走原直连实现（仅关注，不产生事件/被动/通知副作用）
        ApUser apUser = new ApUser();
        apUser.setId(actingUserId);
        AppThreadLocalUtil.setUser(apUser);
        try {
            return fansDataService.followFans(followUserId.intValue());
        } finally {
            AppThreadLocalUtil.clear();
        }
    }

    /**
     * 查询用户 userId 是否已关注 followUserId（供 IM 互关检测等跨服务调用）
     */
    @GetMapping("/isFollowing")
    public ResponseResult isFollowing(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId) {
        if (userId == null || followUserId == null) {
            return ResponseResult.errorResult(400, "userId/followUserId不能为空");
        }
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getUserId, userId.intValue());
        wrapper.eq(ApFollow::getFollowUserId, followUserId.intValue());
        boolean isFollowing = apFollowMapper.selectCount(wrapper) > 0;
        Map<String, Object> result = new HashMap<>();
        result.put("isFollowing", isFollowing);
        return ResponseResult.okResult(result);
    }
}
