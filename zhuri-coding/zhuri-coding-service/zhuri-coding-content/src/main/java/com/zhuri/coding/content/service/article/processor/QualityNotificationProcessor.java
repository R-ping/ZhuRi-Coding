package com.zhuri.coding.content.service.article.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 质量优秀通知处理器
 * 在文章质量评分达到优秀时，发送推荐通知给作者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityNotificationProcessor {

    private final INotificationClient notificationClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 发送质量优秀推荐通知
     * @param article 文章实体
     * @param qualityScore 质量评分
     */
    public void sendQualityNotification(ApArticle article, Integer qualityScore) {
        if (notificationClient == null) {
            log.warn("通知服务不可用，跳过发送质量推荐通知, articleId={}", article.getId());
            return;
        }

        if (article.getAuthorId() == null) {
            log.warn("作者ID为空，跳过发送质量推荐通知, articleId={}", article.getId());
            return;
        }

        try {
            String articleTitle = article.getTitle() != null ? article.getTitle() : "无标题";

            String message = String.format(
                "恭喜！你的文章《%s》因内容质量优秀，已被推荐至首页。",
                articleTitle
            );

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("articleId", String.valueOf(article.getId()));
            contentMap.put("title", articleTitle);
            contentMap.put("qualityScore", qualityScore);
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
                log.info("质量推荐通知已发送, articleId={}, authorId={}, qualityScore={}",
                    article.getId(), article.getAuthorId(), qualityScore);
            } else {
                log.warn("质量推荐通知发送失败, articleId={}, result={}", article.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送质量推荐通知异常, articleId={}, 不影响审核流程", article.getId(), e);
        }
    }
}