package com.heima.content.behavior.controller.v1;

import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.behavior.service.BehaviorHandler;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 统一用户行为控制器
 * 所有用户行为通过此入口处理
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/behavior")
public class BehaviorController {

    @Autowired
    private BehaviorEventBus behaviorEventBus;

    /**
     * 点赞文章/沸点
     * POST /api/v1/behavior/like
     * {"targetType": 1, "targetId": 456, "targetUserId": 789}
     */
    @PostMapping("/like")
    public ResponseResult like(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        Integer targetType = params.get("targetType") != null
            ? Integer.valueOf(params.get("targetType").toString()) : null;
        Long targetId = params.get("targetId") != null
            ? Long.valueOf(params.get("targetId").toString()) : null;
        Integer targetUserId = params.get("targetUserId") != null
            ? Integer.valueOf(params.get("targetUserId").toString()) : null;

        if (targetType == null || targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "targetType和targetId不能为空");
        }

        BehaviorType behaviorType = targetType == 1 ? BehaviorType.LIKE_ARTICLE : BehaviorType.LIKE_PIN;
        BehaviorContext context = new BehaviorContext(behaviorType, user.getId());
        context.withTarget(targetType, targetId)
            .withTargetUser(targetUserId)
            .withUserInfo(user.getNickname(), user.getImage());

        return behaviorEventBus.execute(context);
    }

    /**
     * 取消点赞文章/沸点
     * POST /api/v1/behavior/unlike
     * {"targetType": 1, "targetId": 456}
     */
    @PostMapping("/unlike")
    public ResponseResult unlike(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        Integer targetType = params.get("targetType") != null
            ? Integer.valueOf(params.get("targetType").toString()) : null;
        Long targetId = params.get("targetId") != null
            ? Long.valueOf(params.get("targetId").toString()) : null;

        if (targetType == null || targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "targetType和targetId不能为空");
        }

        BehaviorType behaviorType = targetType == 1 ? BehaviorType.UNLIKE_ARTICLE : BehaviorType.UNLIKE_PIN;
        BehaviorContext context = new BehaviorContext(behaviorType, user.getId());
        context.withTarget(targetType, targetId);

        return behaviorEventBus.rollback(context);
    }

    /**
     * 收藏文章
     * POST /api/v1/behavior/collect
     * {"targetType": 1, "targetId": 456, "targetUserId": 789}
     */
    @PostMapping("/collect")
    public ResponseResult collect(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        Integer targetType = params.get("targetType") != null
            ? Integer.valueOf(params.get("targetType").toString()) : null;
        Long targetId = params.get("targetId") != null
            ? Long.valueOf(params.get("targetId").toString()) : null;
        Integer targetUserId = params.get("targetUserId") != null
            ? Integer.valueOf(params.get("targetUserId").toString()) : null;

        if (targetType == null || targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "targetType和targetId不能为空");
        }

        BehaviorContext context = new BehaviorContext(BehaviorType.COLLECT_ARTICLE, user.getId());
        context.withTarget(targetType, targetId)
            .withTargetUser(targetUserId)
            .withUserInfo(user.getNickname(), user.getImage());

        return behaviorEventBus.execute(context);
    }

    /**
     * 取消收藏文章
     * POST /api/v1/behavior/uncollect
     * {"targetType": 1, "targetId": 456}
     */
    @PostMapping("/uncollect")
    public ResponseResult uncollect(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        Long targetId = params.get("targetId") != null
            ? Long.valueOf(params.get("targetId").toString()) : null;

        if (targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "targetId不能为空");
        }

        BehaviorContext context = new BehaviorContext(BehaviorType.UNCOLLECT_ARTICLE, user.getId());
        context.withTarget(1, targetId);

        return behaviorEventBus.rollback(context);
    }

    /**
     * 记录评论行为（在评论已保存到ap_comment表后调用）
     * POST /api/v1/behavior/comment
     * {"targetType": 1, "targetId": 456, "targetUserId": 789, "commentId": 123, "commentContent": "..."}
     */
    @PostMapping("/comment")
    public ResponseResult comment(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        Integer targetType = params.get("targetType") != null
            ? Integer.valueOf(params.get("targetType").toString()) : null;
        Long targetId = params.get("targetId") != null
            ? Long.valueOf(params.get("targetId").toString()) : null;
        Integer targetUserId = params.get("targetUserId") != null
            ? Integer.valueOf(params.get("targetUserId").toString()) : null;
        Long commentId = params.get("commentId") != null
            ? Long.valueOf(params.get("commentId").toString()) : null;

        if (targetType == null || targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "targetType和targetId不能为空");
        }

        BehaviorType behaviorType = targetType == 1 ? BehaviorType.COMMENT_ARTICLE : BehaviorType.COMMENT_PIN;
        BehaviorContext context = new BehaviorContext(behaviorType, user.getId());
        context.withTarget(targetType, targetId)
            .withTargetUser(targetUserId)
            .withUserInfo(user.getNickname(), user.getImage())
            .withExtra("commentId", commentId)
            .withExtra("commentContent", params.get("commentContent"));

        return behaviorEventBus.execute(context);
    }

    /**
     * 记录浏览行为
     * POST /api/v1/behavior/browse
     * {"targetType": 1, "targetId": 456, "targetUserId": 789}
     */
    @PostMapping("/browse")
    public ResponseResult browse(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            // 浏览行为允许未登录（但记录为空）
            return ResponseResult.okResult();
        }

        Integer targetType = params.get("targetType") != null
            ? Integer.valueOf(params.get("targetType").toString()) : null;
        Long targetId = params.get("targetId") != null
            ? Long.valueOf(params.get("targetId").toString()) : null;
        Integer targetUserId = params.get("targetUserId") != null
            ? Integer.valueOf(params.get("targetUserId").toString()) : null;

        if (targetType == null || targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "targetType和targetId不能为空");
        }

        BehaviorType behaviorType;
        switch (targetType) {
            case 1: behaviorType = BehaviorType.BROWSE_ARTICLE; break;
            case 2: behaviorType = BehaviorType.BROWSE_PIN; break;
            case 4: behaviorType = BehaviorType.BROWSE_COURSE; break;
            default: return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的浏览类型");
        }

        BehaviorContext context = new BehaviorContext(behaviorType, user.getId());
        context.withTarget(targetType, targetId)
            .withTargetUser(targetUserId);

        return behaviorEventBus.execute(context);
    }
}