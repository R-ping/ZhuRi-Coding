package com.heima.content.schedule.listener;

import com.alibaba.fastjson.JSON;
import com.heima.content.event.ScheduleLastDelayTaskEvent;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.schedule.dtos.Task;
import com.heima.utils.common.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 最终延迟任务调度器
 * 监听 ScheduleLastDelayTaskEvent 事件，将延迟发布任务添加到 Redisson 延迟队列。
 * 此组件是唯一依赖 RedissonDelayQueue 的调度器，通过事件驱动与业务逻辑解耦。
 */
@Component
@Slf4j
public class LastDelayTaskScheduler {

    @Autowired
    private RedissonDelayQueue redissonDelayQueue;

    @EventListener
    public void scheduleLastDelay(ScheduleLastDelayTaskEvent event) {
        Long articleId = event.getArticleId();
        long lastExecuteInterval = event.getLastExecuteInterval();

        ApArticle apArticle = new ApArticle();
        apArticle.setId(articleId);
        Task task = new Task();
        task.setParameters(ProtostuffUtil.serialize(apArticle));
        String lastTaskJson = JSON.toJSONString(task);

        redissonDelayQueue.addTask("TASK_LAST_EXECUTE_DELAY_QUEUE", lastTaskJson, lastExecuteInterval);
        log.info("延迟任务添加成功, articleId={}, delay={}ms", articleId, lastExecuteInterval);
    }
}