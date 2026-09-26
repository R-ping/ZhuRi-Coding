package com.zhuri.coding.content.service.outbox.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * 「是否耗尽重试」判定与终态写库的边界测试。
 *
 * <p>这条判定是 Dispatcher 分流「常规重试 vs 终态策略」的唯一依据，边界错一位就会导致
 * 要么少一次重试、要么永远不判死信，所以单独覆盖。
 *
 * <p><b>关于 MybatisPlus lambda 缓存</b>：写库走 {@code LambdaUpdateWrapper}，需在
 * {@code setUp} 中显式预热实体元数据（写法与 {@code PinsReviewServiceTest} 一致），
 * 否则单测会抛 {@code can not find lambda cache for this entity}。
 */
class OutboxServiceImplRetryTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @InjectMocks
    private OutboxServiceImpl outboxService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OutboxEvent.class);
    }

    private OutboxEvent event(Integer retryCount, Integer maxRetries) {
        OutboxEvent e = new OutboxEvent();
        e.setId(1L);
        e.setEventKey("TEST:1");
        e.setEventType("TEST_EVENT");
        e.setPayload("{}");
        e.setRetryCount(retryCount);
        e.setMaxRetries(maxRetries);
        return e;
    }

    @Test
    @DisplayName("retryCount=4 / max=5：本次失败即耗尽")
    void exhaustAtLastAttempt() {
        assertTrue(outboxService.willExhaust(event(4, 5)));
    }

    @Test
    @DisplayName("retryCount=3 / max=5：本次失败后还剩一次")
    void notExhaustBeforeLastAttempt() {
        assertFalse(outboxService.willExhaust(event(3, 5)));
    }

    @Test
    @DisplayName("maxRetries 为 null / 0 / 负数：一律回落到默认上限 5（故 retryCount=4 时即耗尽）")
    void fallsBackToDefaultMaxWhenInvalid() {
        assertTrue(outboxService.willExhaust(event(4, null)), "null 应按默认上限 5 处理");
        assertTrue(outboxService.willExhaust(event(4, 0)), "0 应按默认上限 5 处理");
        assertTrue(outboxService.willExhaust(event(4, -1)), "负数应按默认上限 5 处理");
        // 反向验证：若上限回落生效，则 retryCount=3 时还不该耗尽
        assertFalse(outboxService.willExhaust(event(3, null)));
    }

    @Test
    @DisplayName("retryCount 为 null：按 0 计")
    void nullRetryCountTreatedAsZero() {
        assertFalse(outboxService.willExhaust(event(null, 5)));
        assertTrue(outboxService.willExhaust(event(null, 1)));
    }

    @Test
    @DisplayName("降级收尾：置 DONE 并写库（保留 last_error，便于统计降级率）")
    void markExhaustedDoneWritesBack() {
        outboxService.markExhaustedDone(event(4, 5), "downstream down");
        verify(outboxEventMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("未耗尽时 markFailed：退回 PENDING 并排程退避（非死信）")
    void markFailedUnderLimitSchedulesRetry() {
        outboxService.markFailed(event(2, 5), "transient");
        verify(outboxEventMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }
}
