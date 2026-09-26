package com.zhuri.coding.content.event;

import com.zhuri.coding.content.service.article.ApArticleEventService;
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
 *
 * <p><b>⚠️ 迁移阶段 2（切读）起本类已无事件源，处于待清理状态</b>：
 * {@code ApArticleServiceImpl.createArticleEvent} 不再发布 {@link ArticlePublishEvent}
 * （发布执行改由 Outbox 的 {@code ArticlePublishHandler} 承担），因此本监听器不会再被触发，
 * 上面的「20s 扫描兜底」也已随 {@code ApArticleEventServiceImpl.processEvent}
 * 摘除定时器而失效。本类连同 {@code article_event} 相关代码留待阶段 3 一并清理。
 *
 * <p>之所以<b>保留而不立即删除</b>：切读阶段需要保留完整回退形态 ——
 * 一旦 Outbox 在真实流量下暴露问题，恢复 {@code createArticleEvent} 中的
 * 「写锚点 + 发事件」两行即可让本监听器重新工作，无需重写代码。
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
