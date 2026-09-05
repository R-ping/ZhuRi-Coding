package com.heima.content.event;

import com.heima.content.schedule.service.TaskService;
import com.heima.content.service.article.ApArticleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文章ES同步完成事件监听器
 * 处理文章 ES 同步完成后的最终发布动作：更新 DB/ES 发布状态并消费任务。
 * 单延迟方案：任务到点同步 ES 完成后即置发布态，无二次延迟。
 * 此监听器将 ArticleFreemarkerService 与 RedissonDelayQueue / 任务状态解耦，
 * 从而消除循环依赖链。
 */
@Component
@Slf4j
public class ArticleBuildCompleteEventListener {

    @Autowired
    private ApArticleService apArticleService;
    @Autowired
    private TaskService taskService;

    @EventListener
    public void handleArticleBuildComplete(ArticleBuildCompleteEvent event) {
        Long articleId = event.getArticleId();
        log.info("收到文章ES同步完成事件, articleId={}", articleId);

        // 单延迟：ES 同步完成即置 DB/ES 发布态并消费任务（无延迟发布分支）
        try {
            apArticleService.updateArticleStatus(articleId);
            if (event.getTaskId() != null) {
                taskService.consumerTask(event.getTaskId());
            }
            log.info("文章发布完成, articleId={}", articleId);
        } catch (Exception e) {
            log.error("文章发布异常, articleId={}", articleId, e);
            if (event.getTaskId() != null) {
                try {
                    taskService.failTask(event.getTaskId());
                } catch (Exception ex) {
                    log.error("更新任务日志失败, taskId={}", event.getTaskId(), ex);
                }
            }
        }
    }
}
