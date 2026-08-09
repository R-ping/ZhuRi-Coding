package com.heima.content.event;

import com.heima.content.schedule.service.TaskService;
import com.heima.content.service.article.ApArticleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文章构建完成事件监听器
 * 处理文章 HTML 构建完成后的后续逻辑：立即发布或添加延迟发布任务。
 * 此监听器将 ArticleFreemarkerService 与 RedissonDelayQueue 解耦，
 * 从而消除循环依赖链。
 */
@Component
@Slf4j
public class ArticleBuildCompleteEventListener {

    @Autowired
    private ApArticleService apArticleService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @EventListener
    public void handleArticleBuildComplete(ArticleBuildCompleteEvent event) {
        Long articleId = event.getArticleId();
        log.info("收到文章构建完成事件, articleId={}, hasDelay={}", articleId, event.getLastExecuteInterval() > 0);

        if (event.getLastExecuteInterval() > 0) {
            // 有延迟发布时间：发布调度事件，由 LastDelayTaskScheduler 处理
            eventPublisher.publishEvent(new ScheduleLastDelayTaskEvent(articleId, event.getLastExecuteInterval()));
        } else {
            // 无延迟：立即更新文章状态并完成任务
            try {
                apArticleService.updateArticleStatus(articleId);
                if (event.getTaskId() != null) {
                    taskService.consumerTask(event.getTaskId());
                }
                log.info("文章立即发布完成, articleId={}", articleId);
            } catch (Exception e) {
                log.error("文章立即发布异常, articleId={}", articleId, e);
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
}