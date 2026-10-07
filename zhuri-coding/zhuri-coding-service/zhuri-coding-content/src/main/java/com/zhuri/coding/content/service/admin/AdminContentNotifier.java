package com.zhuri.coding.content.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import com.zhuri.coding.model.admin.AdminContentType;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 「内容折叠 / 解除折叠」告知评论者的站内信投递。
 *
 * <p><b>为什么走本地消息表</b>：与举报回执同一个理由 —— 折叠后评论者还能看见自己的评论，
 * 但列表里已经没人看得见了，这是最需要解释的一种处置。通知服务抖一次就丢掉，
 * 作者就永远不知道为什么评论"没人理"，也不会去申诉。走 {@link LocalMessage}
 * 让它与折叠动作同事务登记、失败自动重试。
 *
 * <p><b>参数按值透传，正文重放时回查</b>：折叠与恢复是两个独立事件（先折后解是常见路径），
 * 所以要能各发一次；但正文、作者这些会变的东西不回档，重放时按 id 重新查 ——
 * 参数里只放"这次是什么动作"。
 *
 * <p><b>为什么方法体不加 @Transactional</b>：一次跨服务调用 + 零次本地写入，
 * 加事务只会让人误以为"通知发失败了业务能一起回滚"——通知已经出网了，回滚不了。
 * 重复投递的代价是作者可能收到两条同样的通知，可以接受；这也是能自动重试的前提。
 */
@Slf4j
@Component
public class AdminContentNotifier {

    /** 事件类型：折叠/解除折叠告知评论者 */
    public static final String EVENT_COMMENT_FOLD_NOTIFY = "ADMIN_COMMENT_FOLD_NOTIFY";

    /** 通知里回显的正文长度：够作者认出是哪条评论即可，不必把整段搬进站内信 */
    private static final int EXCERPT_MAX = 40;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private INotificationClient notificationClient;

    @Autowired
    private ApCommentMapper commentMapper;

    @Autowired
    private ApPinsCommentMapper pinsCommentMapper;

    /**
     * 告知评论者其评论被折叠 / 被恢复。
     *
     * @param targetType 内容类型编码（{@link AdminContentType}）
     * @param commentId  评论ID
     * @param folded     true=已折叠 false=已恢复展示
     * @param reason     处置理由（原样告知评论者）
     */
    @LocalMessage(eventType = EVENT_COMMENT_FOLD_NOTIFY,
        key = "'admin_comment_fold_notify:' + #a0 + ':' + #a1 + ':' + #a2")
    public void notifyCommentAuthor(String targetType, Long commentId, boolean folded, String reason) {
        AdminContentType type = AdminContentType.parse(targetType);
        if (type == null) {
            log.warn("[AdminFold] 通知跳过：内容类型无法识别, targetType={}, commentId={}", targetType, commentId);
            return;
        }
        CommentRef ref = loadComment(type, commentId);
        if (ref == null) {
            log.warn("[AdminFold] 通知跳过：评论不存在, targetType={}, commentId={}", targetType, commentId);
            return;
        }
        if (ref.authorId == null) {
            log.warn("[AdminFold] 通知跳过：评论者账号缺失, targetType={}, commentId={}", targetType, commentId);
            return;
        }

        Map<String, Object> contentMap = new HashMap<>();
        contentMap.put("commentId", String.valueOf(commentId));
        contentMap.put("targetType", type.name());
        contentMap.put("folded", folded);
        contentMap.put("reason", reason);
        contentMap.put("message", text(folded, reason, ref.excerpt));
        contentMap.put("notification_type", "system");
        // 供前端区分跳转：折叠后仍可在内容治理入口发起申诉
        contentMap.put("adminHandleType", folded ? "COMMENT_FOLD" : "COMMENT_UNFOLD");

        Map<String, Object> params = new HashMap<>();
        params.put("userId", ref.authorId.longValue());
        params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
        params.put("sourceId", String.valueOf(commentId));
        try {
            params.put("content", JSON.writeValueAsString(contentMap));
        } catch (Exception e) {
            throw new IllegalStateException("折叠告知内容序列化失败, commentId=" + commentId, e);
        }

        notificationClient.createNotification(params);
        log.info("[AdminFold] 折叠告知已发送, targetType={}, commentId={}, folded={}, to={}",
            type.name(), commentId, folded, ref.authorId);
    }

    /**
     * 折叠与恢复两套文案。
     *
     * <p>折叠不能只说"被隐藏"：作者看到的表象是"评论还在、但没人回我"，容易被理解成限流。
     * 所以要说清楚是<b>被治理折叠</b>、别人看不到、以及可以申诉。
     * 恢复则要明确"已正常展示"，否则作者不敢再评论。
     */
    private String text(boolean folded, String reason, String excerpt) {
        String detail = reason != null && !reason.isBlank() ? reason : "违反社区规范";
        String quote = excerpt == null || excerpt.isBlank() ? "" : "被折叠的评论：" + excerpt + "。";
        if (folded) {
            return quote + "你的这条评论经复核已被折叠，其他用户不会看到它（你自己仍可见）。原因：" + detail
                + "。如有异议可在内容治理入口提交申诉。";
        }
        return quote + "你的这条评论已恢复正常展示。原因：" + detail + "。给你带来的不便敬请谅解。";
    }

    /** 取评论的作者与正文摘录；记录不存在返回 null */
    private CommentRef loadComment(AdminContentType type, Long commentId) {
        if (commentId == null) {
            return null;
        }
        if (type == AdminContentType.COMMENT) {
            ApComment c = commentMapper.selectById(commentId);
            if (c == null) {
                return null;
            }
            return new CommentRef(c.getUserId(), excerpt(c.getContent()));
        }
        ApPinsComment c = pinsCommentMapper.selectById(commentId);
        if (c == null) {
            return null;
        }
        return new CommentRef(c.getUserId(), excerpt(c.getContent()));
    }

    /** 正文摘录：折行会破坏站内信里的一行文案，先压平再截断 */
    private String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_MAX ? flat : flat.substring(0, EXCERPT_MAX) + "…";
    }

    /** 通知所需的最小信息：作者 + 正文摘录 */
    private record CommentRef(Integer authorId, String excerpt) {
    }
}
