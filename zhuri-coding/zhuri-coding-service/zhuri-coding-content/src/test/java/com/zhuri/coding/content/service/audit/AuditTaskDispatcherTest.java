package com.zhuri.coding.content.service.audit;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.audit.ApAuditTaskMapper;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.AuditServiceUnavailableException;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditTaskDispatcher 单测 —— 覆盖三个业务共用的调度语义。
 *
 * <p>这些分支原先在三个 Service 里各写一遍、各测一遍；收敛到这里后只需测一次，
 * 这正是抽 Dispatcher 的收益之一（**测试也从 3 份变 1 份**）。
 *
 * <p><b>关于 MybatisPlus lambda 缓存</b>：CAS / 退避更新都用 {@code LambdaUpdateWrapper}，
 * 故在 {@code setUp} 中显式预热实体元数据（写法与 {@code PinsReviewServiceTest} 一致）。
 */
class AuditTaskDispatcherTest {

    private static final String BIZ = ApAuditTask.BIZ_PINS;

    @Mock
    private ApAuditTaskMapper auditTaskMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApAuditTask.class);
    }

    /** 可编程的测试 Handler */
    static class TestHandler implements AuditTaskHandler {

        private final String bizType;
        AuditResult result = AuditResult.passed();
        RuntimeException auditThrow;
        RuntimeException callbackThrow;
        boolean passedCalled;
        boolean violationCalled;
        boolean degradedCalled;

        TestHandler(String bizType) {
            this.bizType = bizType;
        }

        @Override
        public String bizType() {
            return bizType;
        }

        @Override
        public AuditResult audit(ApAuditTask task) {
            if (auditThrow != null) {
                throw auditThrow;
            }
            return result;
        }

        @Override
        public void onPassed(ApAuditTask task) {
            passedCalled = true;
            maybeThrow();
        }

        @Override
        public void onViolation(ApAuditTask task) {
            violationCalled = true;
            maybeThrow();
        }

        @Override
        public void onDegraded(ApAuditTask task) {
            degradedCalled = true;
            maybeThrow();
        }

        private void maybeThrow() {
            if (callbackThrow != null) {
                throw callbackThrow;
            }
        }
    }

    private ApAuditTask task(String bizType, int retryCount) {
        ApAuditTask t = new ApAuditTask();
        t.setId(1L);
        t.setTaskKey(ApAuditTask.taskKey(bizType, 100L));
        t.setBizType(bizType);
        t.setBizId(100L);
        t.setStatus(ApAuditTask.STATUS_PENDING);
        t.setRetryCount(retryCount);
        return t;
    }

    /** 构造 Dispatcher 并自注册处理器（与生产一致：Handler 在 @PostConstruct 中自注册） */
    private AuditTaskDispatcher dispatcherOf(AuditTaskHandler... handlers) {
        AuditTaskDispatcher dispatcher = new AuditTaskDispatcher(auditTaskMapper);
        for (AuditTaskHandler handler : handlers) {
            dispatcher.register(handler);
        }
        return dispatcher;
    }

    /** CAS 抢占成功，且任务可查到 */
    private void givenClaimed(ApAuditTask t) {
        when(auditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(auditTaskMapper.selectById(t.getId())).thenReturn(t);
    }

    @Test
    @DisplayName("CAS 未抢占成功：直接跳过，不执行审核")
    void casNotAcquiredSkipsAudit() {
        TestHandler handler = new TestHandler(BIZ);
        when(auditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        dispatcherOf(handler).processIfPending(1L);

        assertFalse(handler.passedCalled);
        assertFalse(handler.violationCalled);
    }

    @Test
    @DisplayName("审核通过：置终态并触发 onPassed 回调")
    void passedTriggersCallback() {
        TestHandler handler = new TestHandler(BIZ);
        handler.result = AuditResult.passed();
        givenClaimed(task(BIZ, 0));

        dispatcherOf(handler).processIfPending(1L);

        assertTrue(handler.passedCalled);
        assertFalse(handler.violationCalled);
        // CAS 抢占 + 置终态 = 至少 2 次 update
        verify(auditTaskMapper, org.mockito.Mockito.atLeast(2)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("审核违规：触发 onViolation 而非 onPassed")
    void violationTriggersCallback() {
        TestHandler handler = new TestHandler(BIZ);
        handler.result = AuditResult.failed("含违规词");
        givenClaimed(task(BIZ, 0));

        dispatcherOf(handler).processIfPending(1L);

        assertTrue(handler.violationCalled);
        assertFalse(handler.passedCalled);
    }

    @Test
    @DisplayName("审核服务不可用：退避重试，不误标违规、不触发通过回调")
    void unavailableRetriesWithoutMarkingViolation() {
        TestHandler handler = new TestHandler(BIZ);
        handler.auditThrow = new AuditServiceUnavailableException("AI 不可用");
        givenClaimed(task(BIZ, 0));

        assertDoesNotThrow(() -> dispatcherOf(handler).processIfPending(1L));

        assertFalse(handler.violationCalled, "服务不可用不能判违规");
        assertFalse(handler.passedCalled);
        assertFalse(handler.degradedCalled, "未超限不应降级");
    }

    @Test
    @DisplayName("重试超限：降级放行并触发 onDegraded 回调")
    void exhaustedDegradesWithCallback() {
        TestHandler handler = new TestHandler(BIZ);
        handler.auditThrow = new AuditServiceUnavailableException("AI 不可用");
        // retryCount 已达上限 → 本次失败即超出
        givenClaimed(task(BIZ, ApAuditTask.MAX_RETRY));

        dispatcherOf(handler).processIfPending(1L);

        assertTrue(handler.degradedCalled, "超限应触发降级回调");
        assertFalse(handler.violationCalled, "降级不是违规");
    }

    @Test
    @DisplayName("任务表的 bizType 未注册处理器：按失败走重试，不抛异常")
    void unknownBizTypeFallsBackToRetry() {
        TestHandler handler = new TestHandler(BIZ);
        givenClaimed(task("unknown_biz", 0));

        assertDoesNotThrow(() -> dispatcherOf(handler).processIfPending(1L));

        assertFalse(handler.passedCalled);
        assertFalse(handler.violationCalled);
        assertFalse(handler.degradedCalled);
    }

    @Test
    @DisplayName("同一 bizType 重复注册：Fail-Fast（启动期暴露，不静默覆盖）")
    void duplicateBizTypeFailsFast() {
        AuditTaskDispatcher dispatcher = new AuditTaskDispatcher(auditTaskMapper);
        dispatcher.register(new TestHandler(BIZ));

        assertThrows(IllegalStateException.class, () -> dispatcher.register(new TestHandler(BIZ)));
    }

    @Test
    @DisplayName("终态回调抛异常：不影响已落库的终态，也不向上抛")
    void callbackThrowDoesNotRollback() {
        TestHandler handler = new TestHandler(BIZ);
        handler.callbackThrow = new IllegalStateException("callback bug");
        givenClaimed(task(BIZ, 0));

        assertDoesNotThrow(() -> dispatcherOf(handler).processIfPending(1L));

        assertTrue(handler.passedCalled, "回调确实被调用了，只是失败了");
    }

    @Test
    @DisplayName("入队幂等：唯一键冲突静默跳过，不抛异常")
    void enqueueIdempotentOnDuplicateKey() {
        TestHandler handler = new TestHandler(BIZ);
        when(auditTaskMapper.insert(any(ApAuditTask.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup uk_task_key"));

        assertDoesNotThrow(() -> dispatcherOf(handler).enqueue(task(BIZ, 0)));
    }

    @Test
    @DisplayName("列表查询按 bizType 过滤（防大业务吃光批次配额）")
    void listPendingDueFiltersByBizType() {
        TestHandler handler = new TestHandler(BIZ);
        when(auditTaskMapper.selectList(any())).thenReturn(List.of());

        dispatcherOf(handler).listPendingDue(BIZ, 50);

        verify(auditTaskMapper).selectList(any());
        verify(auditTaskMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }
}
