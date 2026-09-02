package com.heima.content.schedule.service.impl;

import com.heima.common.redis.CacheService;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.schedule.listener.RedissonDelayQueue;
import com.heima.content.schedule.mapper.TaskinfoLogsMapper;
import com.heima.model.schedule.pojos.TaskinfoLogs;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskServiceImpl 单元测试
 *
 * 覆盖 refreshTaskToRedis 的分布式锁语义：锁被占用时跳过，获取锁时执行并释放。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("延迟任务刷新（refreshTaskToRedis）分布式锁测试")
class TaskServiceImplTest {

    @Mock
    private CacheService cacheService;
    @Mock
    private TaskinfoLogsMapper taskinfoLogsMapper;
    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private RedissonDelayQueue redissonDelayQueue;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        when(cacheService.getstringRedisTemplate()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("refreshTaskToRedis - 锁被其他实例占用时跳过本次刷新")
    void skipWhenLockHeld() {
        when(valueOperations.setIfAbsent("task:refresh:lock", "1", java.time.Duration.ofMinutes(25)))
            .thenReturn(false);

        taskService.refreshTaskToRedis();

        // 未获取锁 → 不查库、不投递
        verify(taskinfoLogsMapper, never()).selectGoal(any());
        verify(stringRedisTemplate, never()).delete("task:refresh:lock");
    }

    @Test
    @DisplayName("refreshTaskToRedis - 获取锁后执行刷新并在 finally 释放锁")
    void refreshWhenLockAcquired() {
        when(valueOperations.setIfAbsent("task:refresh:lock", "1", java.time.Duration.ofMinutes(25)))
            .thenReturn(true);
        when(taskinfoLogsMapper.selectGoal(any())).thenReturn(Collections.emptyList());

        taskService.refreshTaskToRedis();

        verify(taskinfoLogsMapper).selectGoal(any());
        verify(stringRedisTemplate).delete("task:refresh:lock");
    }
}
