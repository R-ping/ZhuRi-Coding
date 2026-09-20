package com.zhuri.coding.notification.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.NotificationDto;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.notification.service.NotificationService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    private Long getUserId() {
        ApUser user = AppThreadLocalUtil.getUser();
        return user != null ? user.getId().longValue() : null;
    }

    @GetMapping
    public ResponseResult list(NotificationDto dto) {
        return notificationService.list(dto);
    }

    @PostMapping("/actions/reply")
    public ResponseResult reply(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        Object commentIdObj = body.get("comment_id");
        Object contentObj = body.get("content");
        if (commentIdObj == null || contentObj == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        Long commentId = Long.valueOf(commentIdObj.toString());
        String content = contentObj.toString();
        return notificationService.reply(userId, commentId, content);
    }

    @PostMapping("/actions/like")
    public ResponseResult like(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        Object commentIdObj = body.get("comment_id");
        if (commentIdObj == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        Long commentId = Long.valueOf(commentIdObj.toString());
        return notificationService.toggleLike(userId, commentId);
    }

    @PostMapping("/actions/follow-back")
    public ResponseResult followBack(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        Object followerIdObj = body.get("follower_id");
        if (followerIdObj == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        Long followerId = Long.valueOf(followerIdObj.toString());
        return notificationService.followBack(userId, followerId);
    }

    @GetMapping("/unread-count")
    public ResponseResult unreadCount() {
        Long userId = getUserId();
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return notificationService.unreadCount(userId);
    }

    @PostMapping("/mark-all-read")
    public ResponseResult markAllRead() {
        Long userId = getUserId();
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return notificationService.markAllRead(userId);
    }

    /**
     * 按类型标记已读并清除该类型未读计数，不影响其他类型
     * type 取值：comment / digg / follow / system
     */
    @PostMapping("/mark-type-read")
    public ResponseResult markTypeRead(@RequestParam("type") String type) {
        Long userId = getUserId();
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return notificationService.markTypeRead(userId, type);
    }

    /**
     * Feign内部接口：创建通知
     */
    @PostMapping("/feign/create")
    public ResponseResult createNotification(@RequestBody Map<String, Object> params) {
        Object userIdObj = params.get("userId");
        Object typeObj = params.get("type");
        if (userIdObj == null || typeObj == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        Long userId = Long.valueOf(userIdObj.toString());
        Integer type = Integer.valueOf(typeObj.toString());
        String sourceId = params.get("sourceId") != null ? params.get("sourceId").toString() : null;
        String content = params.get("content") != null ? params.get("content").toString() : null;
        return notificationService.createNotification(userId, type, sourceId, content);
    }

    /**
     * Feign内部接口：增加未读计数
     */
    @PostMapping("/feign/incr-unread")
    public void incrUnread(@RequestParam("userId") Long userId) {
        notificationService.incrUnreadCache(userId);
    }

    /**
     * Feign内部接口：发送活动/促销系统通知
     */
    @PostMapping("/feign/activity")
    public ResponseResult sendActivityNotification(@RequestBody Map<String, Object> params) {
        Object userIdObj = params.get("userId");
        if (userIdObj == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        Long userId = Long.valueOf(userIdObj.toString());
        String title = params.get("title") != null ? params.get("title").toString() : "";
        String content = params.get("content") != null ? params.get("content").toString() : "";
        String link = params.get("link") != null ? params.get("link").toString() : "";
        return notificationService.sendActivityNotification(userId, title, content, link);
    }
}