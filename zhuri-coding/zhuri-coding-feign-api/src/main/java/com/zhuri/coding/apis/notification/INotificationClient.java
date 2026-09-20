package com.heima.apis.notification;

import com.heima.apis.notification.fallback.INotificationClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(value = "leadnews-notification", fallback = INotificationClientFallback.class)
public interface INotificationClient {

    /**
     * 生成通知
     * @param params 包含: userId, type, sourceId, content(JSON)
     */
    @PostMapping("/api/v1/notifications/feign/create")
    ResponseResult createNotification(@RequestBody Map<String, Object> params);

    /**
     * 更新未读计数缓存
     */
    @PostMapping("/api/v1/notifications/feign/incr-unread")
    void incrUnread(@RequestParam("userId") Long userId);

    /**
     * 发送活动/促销系统通知
     * @param params 包含: userId(Long), title(String), content(String), link(String, 可选)
     */
    @PostMapping("/api/v1/notifications/feign/activity")
    ResponseResult sendActivityNotification(@RequestBody Map<String, Object> params);
}