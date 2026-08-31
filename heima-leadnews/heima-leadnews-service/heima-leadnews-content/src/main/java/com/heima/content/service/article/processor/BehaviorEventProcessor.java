package com.heima.content.service.article.processor;

import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 行为事件处理器
 * 在文章审核通过后触发发布文章行为事件（等级积分、统计等）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventProcessor implements ArticleAuditProcessor {

    private final BehaviorEventBus behaviorEventBus;

    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        if (article.getAuthorId() == null) {
            log.info("作者ID为空，跳过行为事件处理, articleId={}", article.getId());
            return true;
        }

        try {
            BehaviorContext behaviorContext = new BehaviorContext(
                BehaviorType.PUBLISH_ARTICLE, article.getAuthorId().intValue());
            behaviorContext.withTarget(1, article.getId())
                .withUserInfo(article.getAuthorName(), article.getAuthorImage());
            ResponseResult result = behaviorEventBus.execute(behaviorContext);
            if (result == null || result.getCode() != 200) {
                log.warn("发布行为事件执行失败, articleId={}, result={}", article.getId(), result);
                throw new AuditRetryableException("发布行为事件执行失败, articleId=" + article.getId());
            }
            log.info("文章发布行为已通过事件总线处理, articleId={}, authorId={}",
                article.getId(), article.getAuthorId());
        } catch (AuditRetryableException e) {
            // 已标记为可重试，直接上抛，交由责任链按阶段重试
            throw e;
        } catch (Exception e) {
            log.error("文章发布行为事件处理异常, articleId={}", article.getId(), e);
            throw new AuditRetryableException("发布行为事件处理异常, articleId=" + article.getId(), e);
        }

        return true;
    }
}