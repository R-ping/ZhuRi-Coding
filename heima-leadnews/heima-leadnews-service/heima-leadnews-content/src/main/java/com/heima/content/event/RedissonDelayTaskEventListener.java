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
                handleFirstExecDelay(task, article);
            } else if ("TASK_LAST_EXECUTE_DELAY_QUEUE".equals(queueName)) {
                handleLastExecDelay(task, article);
            }
        } catch (Exception e) {
            log.error("Redisson延迟任务处理异常, queueName={}", queueName, e);
        }
    }

    /**
     * 处理首次执行延迟任务：生成文章事件并构建 HTML
     */
    private void handleFirstExecDelay(Task task, ApArticle article) {
        log.info("处理首次执行延迟任务，taskId={}, articleId={}", task.getTaskId(), article.getId());
        try {
            long lastExecInterval = task.getObjExecInterval() - task.getFirstExecInterval();
            boolean isArticleEventBuilt = apArticleService.generateArticleEvent(article, task.getTaskId(), lastExecInterval);
            if (isArticleEventBuilt) {
                taskService.consumerTask(task.getTaskId());
            } else {
                taskService.failTask(task.getTaskId());
            }
            log.info("首次执行延迟任务消费成功，taskId={}", task.getTaskId());
        } catch (Exception e) {
            log.error("首次执行延迟任务处理异常，taskId={}", task.getTaskId(), e);
            try {
                taskService.failTask(task.getTaskId());
            } catch (Exception ex) {
                log.error("更新任务日志失败，taskId={}", task.getTaskId(), ex);
            }
        }
    }

    /**
     * 处理最终执行延迟任务：更新文章发布状态
     */
    private void handleLastExecDelay(Task task, ApArticle article) {
        log.info("处理最终执行延迟任务，taskId={}, articleId={}", task.getTaskId(), article.getId());
        try {
            apArticleService.updateArticleStatus(article.getId());
            taskService.consumerTask(task.getTaskId());
            log.info("最终执行延迟任务消费成功，taskId={}, articleId={}", task.getTaskId(), article.getId());
        } catch (Exception e) {
            log.error("最终执行延迟任务处理异常，taskId={}, articleId={}", task.getTaskId(), article.getId(), e);
            try {
                taskService.failTask(task.getTaskId());
            } catch (Exception ex) {
                log.error("更新任务日志失败，taskId={}", task.getTaskId(), ex);
            }
        }
    }
}