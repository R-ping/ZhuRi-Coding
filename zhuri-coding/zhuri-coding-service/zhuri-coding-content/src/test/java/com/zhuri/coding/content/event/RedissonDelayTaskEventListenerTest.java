package com.zhuri.coding.content.event;

import com.alibaba.fastjson.JSON;
import com.zhuri.coding.content.schedule.service.TaskService;
import com.zhuri.coding.content.service.article.ApArticleService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.schedule.dtos.Task;
import com.zhuri.coding.utils.common.ProtostuffUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedissonDelayTaskEventListener 单元测试（延迟任务状态与发布结果解耦语义）
 *
 * <p>核心断言：不管落锚（createArticleEvent）成功/失败/抛异常，
 * handleDelayExec 都必须将延迟任务置为已消费（consumerTask）——
 * 任务状态只表示「到点已触发执行」，与发布结果彻底解耦。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("延迟任务消费与发布结果解耦测试")
class RedissonDelayTaskEventListenerTest {

    @Mock
    private ApArticleService apArticleService;
    @Mock
    private TaskService taskService;

    @InjectMocks
    private RedissonDelayTaskEventListener listener;

    private RedissonDelayTaskEvent buildEvent() {
        ApArticle article = new ApArticle();
        article.setId(1L);
        Task task = new Task();
        task.setTaskId(100L);
        task.setParameters(ProtostuffUtil.serialize(article));
        return new RedissonDelayTaskEvent("TASK_FIRST_EXECUTE_DELAY_QUEUE", JSON.toJSONString(task));
    }

    @Test
    @DisplayName("落锚成功 → 消费任务完成")
    void anchorSuccessThenTaskCompleted() {
        when(apArticleService.createArticleEvent(any(ApArticle.class))).thenReturn(true);

        listener.handleDelayTask(buildEvent());

        verify(taskService).consumerTask(100L);
    }

    @Test
    @DisplayName("落锚返回 false（参数/文章缺失/落库失败）→ 任务仍消费完成")
    void anchorFalseStillTaskCompleted() {
        when(apArticleService.createArticleEvent(any(ApArticle.class))).thenReturn(false);

        listener.handleDelayTask(buildEvent());

        verify(taskService).consumerTask(100L);
    }

    @Test
    @DisplayName("落锚抛异常 → 异常不外抛，任务仍消费完成")
    void anchorThrowsStillTaskCompleted() {
        when(apArticleService.createArticleEvent(any(ApArticle.class)))
                .thenThrow(new RuntimeException("db down"));

        listener.handleDelayTask(buildEvent());

        verify(taskService).consumerTask(100L);
    }

    @Test
    @DisplayName("消费任务置状态失败 → 异常吞掉不阻断消费线程")
    void consumerTaskFailsSwallowed() {
        when(apArticleService.createArticleEvent(any(ApArticle.class))).thenReturn(true);
        doThrow(new RuntimeException("update fail")).when(taskService).consumerTask(100L);

        listener.handleDelayTask(buildEvent());

        verify(apArticleService).createArticleEvent(any(ApArticle.class));
    }

    @Test
    @DisplayName("未知队列 → 不触发落锚也不消费任务")
    void unknownQueueIgnored() {
        listener.handleDelayTask(new RedissonDelayTaskEvent("UNKNOWN_QUEUE", "{}"));

        verify(apArticleService, never()).createArticleEvent(any());
        verify(taskService, never()).consumerTask(anyLong());
    }
}
