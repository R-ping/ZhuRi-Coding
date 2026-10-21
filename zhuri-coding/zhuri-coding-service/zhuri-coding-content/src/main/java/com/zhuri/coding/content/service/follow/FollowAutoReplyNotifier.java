package com.zhuri.coding.content.service.follow;

import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 关注成功后的自动私信投递。
 *
 * <p><b>为什么走本地消息表</b>：自动私信是附加价值，关注才是主链路。同步调用私信服务写库，
 * 一旦对端抖动，用户看到的就是「关注失败」；走高热度账号涨粉时，同步写还会被放大成写入风暴。
 * 这里只在关注事务内登记一条事件（幂等键 = 关注关系 ID），提交后由调度器重放，
 * 失败自动重试，关注结果不受影响。
 *
 * <p><b>参数全部按值透传</b>：三个 ID 都不会变，重放时无需回查关注表 ——
 * 用户关注后立即取关会把关系行删掉，回查就再也发不出这条消息了。
 */
@Slf4j
@Component
public class FollowAutoReplyNotifier {

    public static final String EVENT_FOLLOW_AUTO_REPLY = "FOLLOW_AUTO_REPLY";

    @Autowired
    private INotificationClient notificationClient;

    /**
     * 登记并（在事务提交后）投递自动私信。
     *
     * <p>方法体必须可安全重试：重复投递由私信侧的 {@code source_type + source_id} 唯一索引兜底，
     * 第二次写入会被拦下并按已发送处理。
     */
    @LocalMessage(eventType = EVENT_FOLLOW_AUTO_REPLY, key = "'follow_auto_reply:' + #a0")
    public void notifyFollowed(Long followId, Integer followerId, Integer followeeId) {
        if (followId == null || followerId == null || followeeId == null) {
            log.warn("[AutoReply] 参数不完整，跳过投递: followId={}, follower={}, followee={}",
                followId, followerId, followeeId);
            return;
        }
        ResponseResult result = notificationClient.sendFollowAutoReply(
            followId, followerId.longValue(), followeeId.longValue());
        if (result == null || result.getCode() != 200) {
            // 抛异常交给重试；返回 ok 会让调度器认为已完成，消息就永久丢了
            throw new IllegalStateException("关注自动私信投递失败, followId=" + followId
                + ", code=" + (result == null ? "null" : result.getCode()));
        }
    }
}
