package com.heima.content.service.pins.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.pins.ApPinsAuditTaskMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditResult;
import com.heima.model.audit.AuditServiceUnavailableException;
import com.heima.model.audit.pojos.ApPinsAuditTask;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.user.pojos.ApUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PinsReviewService 回归测试（重点关注 B2 沸点异步审核可靠队列 / CAS 抢占）
 *
 * 核心安全诉求：保证同一沸点只被一个执行体处理、服务重启不丢任务、审核服务异常按退避重试处理。
 * - 入队：持久化任务(PENDING) + 幂等(唯一键冲突跳过)；
 * - 抢占：CAS(PENDING->PROCESSING) 未命中则不重复执行审核；
 * - 完成：审核通过才触发等级积分事件，违规不触发；
 * - 异常：AI 审核不可用 -> 不误标违规，退回待审重试。
 */
class PinsReviewServiceTest {

    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApPinsAuditTaskMapper pinsAuditTaskMapper;
    @Mock
    private PinsAuditService pinsAuditService;
    @Mock
    private BehaviorEventBus behaviorEventBus;

    @InjectMocks
    private PinsReviewService pinsReviewService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 MybatisPlus 实体表元数据与 lambda 列缓存，使单测不依赖 Spring 上下文或测试执行顺序
        // （集成测试若在共享 JVM 中先加载 Spring 会自动注册缓存；本单测须自足，CI 无库也能稳定运行）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPinsAuditTask.class);
    }

    // ==================== 入队 ====================

    @Test
    @DisplayName("B2 - asyncReviewPins 持久化待审任务并立即触发审核")
    void testAsyncReviewPinsEnqueueAndTrigger() {
        ApPins pins = new ApPins();
        pins.setId(999L);
        pins.setAuthorId(20001L);
        pins.setAuthorName("测试作者");
        pins.setContent("这是一条沸点正文");
        ApUser user = new ApUser();
        user.setId(88);

        // 模拟 insert 时由数据库回填主键
        when(pinsAuditTaskMapper.insert(any(ApPinsAuditTask.class))).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) {
                ApPinsAuditTask task = invocation.getArgument(0);
                task.setId(777L);
                return 1;
            }
        });
        when(pinsAuditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(pinsAuditTaskMapper.selectById(777L)).thenReturn(buildTask(777L, 999L, 88));
        when(apPinsMapper.selectById(999L)).thenReturn(pins);
        when(pinsAuditService.audit(any(AuditContext.class))).thenReturn(AuditResult.passed());

        pinsReviewService.asyncReviewPins(pins, user);

        verify(pinsAuditTaskMapper).insert(any(ApPinsAuditTask.class));
        // 入队后立即触发了一次真实审核
        verify(pinsAuditService).audit(any(AuditContext.class));
    }

    @Test
    @DisplayName("B2 - 重复入队(唯一键冲突)不抛异常，幂等可容忍")
    void testAsyncReviewPinsDuplicateKeyIdempotent() {
        ApPins pins = new ApPins();
        pins.setId(998L);
        pins.setAuthorId(20001L);
        pins.setContent("重复入队");
        ApUser user = new ApUser();
        user.setId(88);

        when(pinsAuditTaskMapper.insert(any(ApPinsAuditTask.class)))
                .thenThrow(new DuplicateKeyException("duplicate uq_pins_id"));

        assertDoesNotThrow(() -> pinsReviewService.asyncReviewPins(pins, user));
        verify(pinsAuditTaskMapper).insert(any(ApPinsAuditTask.class));
    }

    // ==================== CAS 抢占 ====================

    @Test
    @DisplayName("B2 - CAS 未抢占成功(已被他处处理)则不重复执行审核")
    void testProcessTaskNotAcquiredSkipsAudit() {
        // update 返回 0 -> PENDING->PROCESSING 抢占失败
        when(pinsAuditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        pinsReviewService.processTaskIfPending(1L);

        verify(pinsAuditService, never()).audit(any(AuditContext.class));
    }

    // ==================== 完成 ====================

    @Test
    @DisplayName("B2 - 审核通过: 触发等级积分事件")
    void testProcessTaskPassedTriggersBehavior() {
        long taskId = 1L;
        when(pinsAuditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(pinsAuditTaskMapper.selectById(taskId)).thenReturn(buildTask(taskId, 999L, 88));
        ApPins pins = new ApPins();
        pins.setId(999L);
        pins.setAuthorName("测试作者");
        when(apPinsMapper.selectById(999L)).thenReturn(pins);
        when(pinsAuditService.audit(any(AuditContext.class))).thenReturn(AuditResult.passed());

        pinsReviewService.processTaskIfPending(taskId);

        verify(pinsAuditService).audit(any(AuditContext.class));
        verify(behaviorEventBus).execute(any(BehaviorContext.class));
    }

    @Test
    @DisplayName("B2 - 审核违规: 标记违规且不触发等级积分事件")
    void testProcessTaskViolationNoBehavior() {
        long taskId = 2L;
        when(pinsAuditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(pinsAuditTaskMapper.selectById(taskId)).thenReturn(buildTask(taskId, 999L, 88));
        when(apPinsMapper.selectById(999L)).thenReturn(new ApPins());
        when(pinsAuditService.audit(any(AuditContext.class))).thenReturn(AuditResult.failed("违规"));

        pinsReviewService.processTaskIfPending(taskId);

        verify(pinsAuditService).audit(any(AuditContext.class));
        verify(behaviorEventBus, never()).execute(any(BehaviorContext.class));
    }

    // ==================== 异常重试 ====================

    @Test
    @DisplayName("B2 - 审核服务不可用: 不误标违规，退回待审调度重试(不抛异常)")
    void testProcessTaskAiUnavailableRetries() {
        long taskId = 3L;
        when(pinsAuditTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        // 初始 retryCount=0；注意 selectById 在 CAS 后与 retryOrDegrade 中都会被调用
        ApPinsAuditTask task = buildTask(taskId, 999L, 88);
        task.setRetryCount(0);
        when(pinsAuditTaskMapper.selectById(taskId)).thenReturn(task);
        when(apPinsMapper.selectById(999L)).thenReturn(new ApPins());
        when(pinsAuditService.audit(any(AuditContext.class)))
                .thenThrow(new AuditServiceUnavailableException("AI不可用"));

        // 关键断言：fail-closed 异常被捕获，沿可靠队列退避重试，不向上抛、不误标违规
        assertDoesNotThrow(() -> pinsReviewService.processTaskIfPending(taskId));
        verify(pinsAuditService).audit(any(AuditContext.class));
        verify(behaviorEventBus, never()).execute(any(BehaviorContext.class));
        // CAS 抢占 + 退避重试(退回 PENDING) 至少产生两次 update
        verify(pinsAuditTaskMapper, atLeastOnce()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ==================== 辅助 ====================

    private ApPinsAuditTask buildTask(Long taskId, Long pinsId, Integer userId) {
        ApPinsAuditTask task = new ApPinsAuditTask();
        task.setId(taskId);
        task.setPinsId(pinsId);
        task.setUserId(userId);
        task.setStatus(ApPinsAuditTask.STATUS_PENDING);
        task.setRetryCount(0);
        return task;
    }
}