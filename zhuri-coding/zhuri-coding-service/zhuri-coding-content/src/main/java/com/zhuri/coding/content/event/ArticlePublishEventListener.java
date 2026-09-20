package com.heima.content.event;

import com.heima.content.service.article.ApArticleEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 文章发布异步执行监听器
 *
 * <p>职责：消费 {@link ArticlePublishEvent}，执行「置 DB 发布态 + ES 同步 + 状态机收敛」。
 * 与 20s 补偿扫描共用 ApArticleEventService#executePublish 同一套幂等执行体（主流程=补偿流程）。
 *
 * <p>解耦边界：本监听器属于发布业务侧，任何异常都不回传延迟任务层——
 * 延迟任务在落锚后即已置 COMPLETED；本监听器崩溃/应用重启导致的 INIT 滞留，
 * 由 20s 扫描按「INIT 滞留超 60s 重放」兜底收敛。
 */
@Component
@Slf4j
public class ArticlePublishEventListener {

    @Autowired
    private ApArticleEventService apArticleEventService;

    /**
     * 异步执行发布主流程：置 DB 发布态（幂等条件更新）→ ES 同步 → 状态机收敛。
     * 异常吞掉只记日志：执行中断由本地消息表状态机 + 20s 扫描接管，不阻塞事件发布方。
     */
    @Async
    @EventListener
    public void onArticlePublish(ArticlePublishEvent event) {
        try {
            apArticleEventService.executePublish(event.getArticleId());
            log.info("文章发布异步执行完成, articleId={}", event.getArticleId());
        } catch (Exception e) {
            log.error("文章发布异步执行异常，交由20s扫描补偿, articleId={}", event.getArticleId(), e);
        }
    }
}
