package com.zhuri.coding.content.service.pins.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.audit.AuditTaskDispatcher;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.user.pojos.ApUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PinsReviewService 单测（薄壳版）。
 *
 * <p><b>测试范围的变化</b>：抽取 {@link AuditTaskDispatcher} 之后，
 * CAS 抢占 / 指数退避 / 超限降级放行等**调度语义已由 {@code AuditTaskDispatcherTest} 统一覆盖**，
 * 本类只覆盖沸点业务自己的三件事：
 * <ol>
 *   <li>入队字段映射（bizType / bizKey / bizId / actorUserId）与触发；</li>
 *   <li>{@code audit} **回查沸点表取最新内容**（区别于评论类读任务表快照）；</li>
 *   <li>{@code onPassed} 触发"发布沸点"等级积分事件。</li>
 * </ol>
 * 外加"调度调用是否如实委托给 Dispatcher"。
 */
class PinsReviewServiceTest {

    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private AuditTaskDispatcher auditTaskDispatcher;
    @Mock
    private PinsAuditService pinsAuditService;
    @Mock
    private BehaviorEventBus behaviorEventBus;

    @InjectMocks
    private PinsReviewService pinsReviewService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 lambda 列缓存，使单测不依赖 Spring 上下文（与项目内其它测试一致）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApAuditTask.class);
    }

    private ApAuditTask buildTask(Long pinsId) {
        ApAuditTask task = new ApAuditTask();
        task.setId(1L);
        task.setTaskKey(ApAuditTask.taskKey(ApAuditTask.BIZ_PINS, pinsId));
        task.setBizType(ApAuditTask.BIZ_PINS);
        task.setBizId(pinsId);
        task.setStatus(ApAuditTask.STATUS_PROCESSING);
        task.setRetryCount(0);
        return task;
    }

    // ==================== 入队 ====================

    @Test
    @DisplayName("入队：字段映射正确，并在写入后立即触发一次")
    void enqueueMapsFieldsAndTriggers() {
        ApPins pins = new ApPins();
        pins.setId(999L);
        pins.setAuthorId(20001L);
        pins.setAuthorName("测试作者");
        pins.setContent("这是一条沸点正文");
        ApUser user = new ApUser();
        user.setId(88);

        // 模拟数据库回填主键，同时校验统一表的字段映射
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) {
                ApAuditTask task = invocation.getArgument(0);
                assertEquals(ApAuditTask.BIZ_PINS, task.getBizType());
                assertEquals(ApAuditTask.taskKey(ApAuditTask.BIZ_PINS, 999L), task.getTaskKey());
                assertEquals(999L, task.getBizId());
                assertEquals(88, task.getActorUserId(), "user 优先作为行为用户");
                assertEquals(20001, task.getAuthorId());
                assertEquals(ApAuditTask.STATUS_PENDING, task.getStatus());
                task.setId(777L);
                return null;
            }
        }).when(auditTaskDispatcher).enqueue(any(ApAuditTask.class));

        pinsReviewService.asyncReviewPins(pins, user);

        verify(auditTaskDispatcher).enqueue(any(ApAuditTask.class));
        verify(auditTaskDispatcher).processIfPending(777L);
    }

    @Test
    @DisplayName("入队：沸点或ID为空时直接返回，不写库")
    void enqueueSkipsWhenPinsMissing() {
        pinsReviewService.asyncReviewPins(null, new ApUser());
        verify(auditTaskDispatcher, never()).enqueue(any(ApAuditTask.class));

        pinsReviewService.asyncReviewPins(new ApPins(), new ApUser());
        verify(auditTaskDispatcher, never()).enqueue(any(ApAuditTask.class));
    }

    // ==================== 审核 ====================

    @Test
    @DisplayName("审核：回查沸点表取最新内容（而非任务表快照）")
    void auditReadsLatestPinsContent() {
        ApPins pins = new ApPins();
        pins.setId(999L);
        pins.setContent("作者刚刚修改过的最新内容");
        pins.setAuthorId(20001L);
        pins.setAuthorName("测试作者");
        when(apPinsMapper.selectById(999L)).thenReturn(pins);
        when(pinsAuditService.audit(any(AuditContext.class))).thenReturn(AuditResult.passed());

        AuditResult result = pinsReviewService.audit(buildTask(999L));

        assertTrue(result.isPassed());
        verify(apPinsMapper).selectById(999L);
        verify(pinsAuditService).audit(any(AuditContext.class));
    }

    @Test
    @DisplayName("审核：沸点不存在时返回失败结果，不调用审核服务")
    void auditMissingPinsReturnsFailed() {
        when(apPinsMapper.selectById(any())).thenReturn(null);

        AuditResult result = pinsReviewService.audit(buildTask(999L));

        assertFalse(result.isPassed());
        verify(pinsAuditService, never()).audit(any(AuditContext.class));
    }

    // ==================== 终态回调 ====================

    @Test
    @DisplayName("onPassed：触发发布沸点的等级积分事件")
    void onPassedTriggersBehaviorEvent() {
        ApAuditTask task = buildTask(999L);
        task.setActorUserId(88);
        ApPins pins = new ApPins();
        pins.setAuthorName("测试作者");
        when(apPinsMapper.selectById(999L)).thenReturn(pins);

        pinsReviewService.onPassed(task);

        verify(behaviorEventBus).execute(any(BehaviorContext.class));
    }

    @Test
    @DisplayName("onPassed：缺少行为用户时不触发事件（避免给不存在的用户记积分）")
    void onPassedSkippedWithoutActor() {
        ApAuditTask task = buildTask(999L);
        task.setActorUserId(null);

        pinsReviewService.onPassed(task);

        verify(behaviorEventBus, never()).execute(any(BehaviorContext.class));
    }

    // ==================== 调度委托 ====================

    @Test
    @DisplayName("调度委托：processTaskIfPending / listPendingDue 原样转交 Dispatcher")
    void delegatesSchedulingToDispatcher() {
        pinsReviewService.processTaskIfPending(1L);
        verify(auditTaskDispatcher).processIfPending(1L);

        pinsReviewService.listPendingDue(50);
        verify(auditTaskDispatcher).listPendingDue(ApAuditTask.BIZ_PINS, 50);
    }
}
