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
     * 创建「收藏文章更新」聚合提醒：同一用户同一天的多条更新合并为一条站内信。
     * @param params 包含: userId(Long), dayKey(yyyy-MM-dd), content(JSON字符串)
     */
    @PostMapping("/api/v1/notifications/feign/create-collect-update")
    ResponseResult createCollectUpdateNotification(@RequestBody Map<String, Object> params);

    /**
     * 发送活动/促销系统通知
     * @param params 包含: userId(Long), title(String), content(String), link(String, 可选)
     */
    @PostMapping("/api/v1/notifications/feign/activity")
    ResponseResult sendActivityNotification(@RequestBody Map<String, Object> params);

    /**
     * 关注成功后投递一条自动私信。
     *
     * <p>投递由关注服务在关注事务提交后经本地消息表重放调用；{@code followId} 同时是对方侧的
     * 幂等键，重复调用不会产生第二条消息。返回非 200 时调用方应抛异常交给重试。
     *
     * @param followId   关注关系 ID（幂等键）
     * @param followerId 触发方（粉丝）
     * @param followeeId 配置方（被关注者，消息以他的身份发出）
     */
    @PostMapping("/api/v1/im/feign/follow-auto-reply")
    ResponseResult sendFollowAutoReply(@RequestParam("followId") Long followId,
                                       @RequestParam("followerId") Long followerId,
                                       @RequestParam("followeeId") Long followeeId);
}