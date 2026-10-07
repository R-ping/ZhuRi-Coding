package com.zhuri.coding.user.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 「用户处置」告知当事人的站内信投递。
 *
 * <p><b>为什么不用本地消息表</b>：本地消息表（Outbox）目前只在 content 模块，
 * user 模块没有那套基础设施。搬一套过来是更彻底的解法（见下方"遗留"），
 * 本次先用直投 + 调用方决定失败语义的方式实现。
 *
 * <p><b>遗留（明确的后续项）</b>：content 模块的 {@code service.outbox} 那套
 * （{@code @LocalMessage} 注解 + 定时重放）应该下沉到 common 供两边共用。在此之前，
 * 处置通知的可靠性由调用方兜：
 * <ul>
 *   <li><b>警告</b>走"通知失败即整体失败"—— 警告的效果本来就只有这条通知，
 *       发不出去就等于没警告，让运营看到失败去重试是对的；</li>
 *   <li><b>封禁/解封</b>走 best-effort —— 账号状态才是封禁本身，通知服务抖一下不该让封禁回滚。
 *       而且被封的人压根登不进来，站内信本就不是他的权威告知渠道，
 *       真正的告知在登录报错里（见 {@code UserBanChecker}）。</li>
 * </ul>
 */
@Slf4j
@Component
public class UserDispositionNotifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 前端据此区分跳转与文案：警告 / 封禁 / 解封 */
    public static final String HANDLE_WARN = "USER_WARN";
    public static final String HANDLE_BAN = "USER_BAN";
    public static final String HANDLE_UNBAN = "USER_UNBAN";

    @Autowired
    private INotificationClient notificationClient;

    /**
     * 向当事人投递一条处置通知。
     *
     * <p><b>不吞异常</b>：失败语义由调用方决定（见类注释），这里只负责"发"，
     * 抛出去让调用方选择回滚还是记一笔日志。吞掉异常会让两种完全不同的失败模式变得无法区分。
     *
     * @param targetUserId 收件人（被处置的账号）
     * @param handleType   处置类型，见本类的 {@code HANDLE_*} 常量
     * @param message      对当事人可见的完整文案（由业务侧组装，本类不再二次加工）
     */
    public void notify(String targetUserId, String handleType, String message) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new IllegalArgumentException("收件人账号ID为空，无法投递处置通知");
        }
        Map<String, Object> contentMap = new HashMap<>();
        contentMap.put("message", message);
        // 与其他系统通知保持同一形态（见 NotificationProcessor），前端按这两个字段渲染样式与入口
        contentMap.put("notification_type", "system");
        contentMap.put("adminHandleType", handleType);

        Map<String, Object> params = new HashMap<>();
        params.put("userId", Long.valueOf(targetUserId));
        params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
        // sourceId 用收件人自己的账号ID：这类通知不聚合、一行一事件，sourceId 只作留痕
        params.put("sourceId", targetUserId);
        try {
            params.put("content", JSON.writeValueAsString(contentMap));
        } catch (Exception e) {
            throw new IllegalStateException("处置通知内容序列化失败, targetUserId=" + targetUserId, e);
        }

        notificationClient.createNotification(params);
        log.info("[UserDisposition] 处置通知已发送, to={}, handleType={}", targetUserId, handleType);
    }
}
