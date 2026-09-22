package com.zhuri.coding.apis.notification;

import com.zhuri.coding.apis.notification.fallback.INotificationClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 通知服务 Feign 客户端。
 *
 * <p><b>注意 fallback 与 fallbackFactory 的区别</b>：{@code INotificationClientFallback} 实现的是
 * {@code FallbackFactory<INotificationClient>}，因此这里必须用 {@code fallbackFactory} 属性，
 * 用 {@code fallback} 会在创建 Bean 时抛
 * {@code Incompatible fallback instance ... is not assignable to interface}（本仓曾如此，
 * 因 fallback 全程未生效而长期未被发现）。
 */
@FeignClient(value = "zhuri-coding-notification", fallbackFactory = INotificationClientFallback.class)
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