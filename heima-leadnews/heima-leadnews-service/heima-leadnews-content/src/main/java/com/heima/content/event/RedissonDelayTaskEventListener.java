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
 * Redisson 延迟队列任务事件监听器
 * 处理 RedissonDelayQueue 发布的延迟任务事件，
 * 负责调用 ApArticleService 和 TaskService 执行具体业务逻辑。
 * 此监听器将 RedissonDelayQueue 与业务服务解耦，从而打破循环依赖链。
 *
 * <p>单延迟方案：任务在 publishTime 触发一次消费，完整链路
 * 本地消息表入库 → ES 同步 → 事件（监听器置 DB/ES 发布态 + 消费任务）在单次消费内完成，
 * 不再需要"提前执行复杂业务 + 二次延迟置可见"的双延迟方案。
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
     * 处理单延迟任务：一次消费完成文章发布链路
     * （本地消息表入库 + ES 同步 + 事件发布，事件监听器再置 DB/ES 发布态并消费任务）
     */
    private void handleDelayExec(Task task, ApArticle article) {
        log.info("处理延迟发布任务，taskId={}, articleId={}", task.getTaskId(), article.getId());
        try {
            boolean isArticleEventBuilt = apArticleService.generateArticleEvent(article, task.getTaskId());
            if (isArticleEventBuilt) {
                taskService.consumerTask(task.getTaskId());
            } else {
                taskService.failTask(task.getTaskId());
            }
            log.info("延迟发布任务消费成功，taskId={}", task.getTaskId());
        } catch (Exception e) {
            log.error("延迟发布任务处理异常，taskId={}", task.getTaskId(), e);
            try {
                taskService.failTask(task.getTaskId());
            } catch (Exception ex) {
                log.error("更新任务日志失败，taskId={}", task.getTaskId(), ex);
            }
        }
    }
}
