package com.heima.content.service.article.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.AuditRecordService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.common.dtos.ResponseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 审核失败处理器
 * 负责处理审核失败时的后续操作：更新文章状态、写入审计记录、发送通知
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditFailProcessor {

    private final ApArticleMapper apArticleMapper;
    private final AuditRecordService auditRecordService;
    private final INotificationClient notificationClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统级审核异常（辅助环节重试耗尽或未预期异常）的终态失败原因，用于告知作者重新提交 */
    public static final String SYSTEM_ERROR_REASON = "系统审核异常，审核未完成，请稍后重新提交";

    /**
     * 处理审核失败
     * @param article 文章实体
     * @param failReason 失败原因
     */
    public void handleFail(ApArticle article, String failReason) {
        if (article == null || failReason == null) {
            return;
        }

        article.setStatus(Status.FAIL.getCode());
        article.setReason(failReason);

        // 更新文章状态
        apArticleMapper.updateById(article);

        // 写入审计记录（失败轨迹，内容由审计服务兜底查询）
        auditRecordService.record(article, null, ArticleConstants.AUDIT_STATUS_FAIL, failReason);

        // 发送系统通知
        sendModerationFailNotification(article, failReason);
    }

    /**
     * 发送审核失败系统通知
     */
    private void sendModerationFailNotification(ApArticle article, String reason) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送审核失败通知, articleId={}", article.getId());
                return;
            }

            String articleTitle = article.getTitle() != null ? article.getTitle() : "无标题";
            String violationDetails = reason != null ? reason : "违反社区规范";

            String message = String.format(
                "你的文章《%s》因违反社区规范已被删除。详细规则请见《社区规范》。文章内容: %s",
                articleTitle, violationDetails
            );

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("articleId", String.valueOf(article.getId()));
            contentMap.put("title", articleTitle);
            contentMap.put("reason", violationDetails);
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");

            String contentJson = objectMapper.writeValueAsString(contentMap);

            Map<String, Object> params = new HashMap<>();
            params.put("userId", article.getAuthorId());
            params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
            params.put("sourceId", String.valueOf(article.getId()));
            params.put("content", contentJson);

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("审核失败通知已发送, articleId={}, authorId={}", article.getId(), article.getAuthorId());
            } else {
                log.warn("审核失败通知发送失败, articleId={}, result={}", article.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送审核失败通知异常, articleId={}, 不影响审核流程", article.getId(), e);
        }
    }
}