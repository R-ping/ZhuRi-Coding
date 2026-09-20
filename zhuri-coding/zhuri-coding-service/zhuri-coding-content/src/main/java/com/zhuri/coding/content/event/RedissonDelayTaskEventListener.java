package com.heima.content.event;

import com.alibaba.fastjson.JSON;
import com.heima.content.schedule.service.TaskService;
import com.heima.content.service.article.ApArticleService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.schedule.dtos.Task;
import com.heima.utils.common.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Redisson 延迟队列任务事件监听器 处理 RedissonDelayQueue 发布的延迟任务事件， 负责调用 ApArticleService 和 TaskService 执行具体业务逻辑。 此监听器将
 * RedissonDelayQueue 与业务服务解耦，从而打破循环依赖链。
 *
 * <p>单延迟方案 · 异步解耦版：任务在 publishTime 触发一次消费，链路为
 * 本地消息表落锚(INIT) → 发布 ArticlePublishEvent（@Async 监听器异步置位 + 同步 ES）→ 任务置 COMPLETED。
 * 延迟任务状态只表示「到点已触发执行」，与发布结果彻底解耦；
 * 发布未完成由 20s 扫描按本地消息表状态机补偿收敛。
 */
@Component
@Slf4j
public class RedissonDelayTaskEventListener {

    @Autowired
    private ApArticleService apArticleService;

    @Autowired
    private TaskService taskService;

    @EventListener
    public void handleDelayTask(RedissonDelayTaskEvent event) {
        String queueName = event.getQueueName();
        String taskJson = event.getTaskJson();

        try {
            Task task = JSON.parseObject(taskJson, Task.class);
            ApArticle article = ProtostuffUtil.deserialize(task.getParameters(), ApArticle.class);

            if ("TASK_FIRST_EXECUTE_DELAY_QUEUE".equals(queueName)) {
                handleDelayExec(task, article);
            } else {
                log.warn("未知延迟队列, queueName={}", queueName);
            }
        } catch (Exception e) {
            log.error("Redisson延迟任务处理异常, queueName={}", queueName, e);
        }
    }

    /**
     * 处理单延迟任务：本地消息表落锚后任务即消费完成——不管落锚/发布成败都置 COMPLETED。
     *
     * <p>解耦依据：taskinfo_logs 状态只表示「到点已触发执行」。若落锚失败仍保持 PROGRESSING：
     * in_one_hour=1（1 小时内发布）的任务不会被 selectGoal 捞出重投，永久滞留；
     * in_one_hour=0 的任务会被 30min 刷新重投递，造成重复消费风暴。
     * 发布执行（置位 + ES 同步）由 {@link ArticlePublishEventListener} 异步完成，失败由 20s 扫描补偿。
     */
    private void handleDelayExec(Task task, ApArticle article) {
        log.info("处理延迟发布任务，taskId={}, articleId={}", task.getTaskId(), article.getId());
        boolean anchored = false;
        try {
            anchored = apArticleService.createArticleEvent(article);
        } catch (Exception e) {
            log.error("延迟发布任务落锚异常, taskId={}, articleId={}", task.getTaskId(), article.getId(), e);
        } finally {
            if (!anchored) {
                log.error("延迟发布任务落锚未成功（参数/文章缺失或落库失败），文章将滞留待人工排查, taskId={}, articleId={}",
                    task.getTaskId(), article.getId());
            }
            // 任务到点即视为已执行：置 COMPLETED 与发布结果无关（重试责任已移交本地消息表状态机 + 扫描）
            try {
                taskService.consumerTask(task.getTaskId());
                log.info("延迟发布任务消费完成(与发布结果解耦), taskId={}", task.getTaskId());
            } catch (Exception ex) {
                log.error("更新任务状态失败, taskId={}", task.getTaskId(), ex);
            }
        }
    }
}
