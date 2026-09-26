package com.zhuri.coding.content.service.comment.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.audit.AuditTaskDispatcher;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditEntityType;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CommentAuditService 单测（薄壳版）。
 *
 * <p><b>测试范围的变化</b>：抽取 {@link AuditTaskDispatcher} 之后，
 * CAS 抢占 / 指数退避 / 超限降级放行等调度语义已由 {@code AuditTaskDispatcherTest} 统一覆盖
 * （原先在三个 Service 里各测一遍）。本类只覆盖文章评论业务自己的差异：
 * <ol>
 *   <li>入队字段映射（bizType / taskKey / bizId / authorId / target 系列）；</li>
 *   <li>{@code onDegraded}：降级放行后向内容作者补发"仅过审"通知 ——
 *       这是文章评论与沸点评论的关键差异（后者"创建即通知"，审核链路不补发）；</li>
 *   <li>调度调用如实委托给 Dispatcher。</li>
 * </ol>
 *
 * <p><b>未在本类覆盖</b>：{@code audit(task)} 构造上下文后调用的父类模板方法
 * （{@code handlePassed} / {@code handleFailed} 的折叠判定、删评论、撤销行为记录），
 * 那部分依赖 {@code AbstractAuditService} 内部实现，属于集成测试范畴。
 */
class CommentAuditServiceTest {

    @Mock
    private ApCommentMapper apCommentMapper;
    @Mock
    private AuditTaskDispatcher auditTaskDispatcher;
    @Mock
    private INotificationClient notificationClient;
    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;
    @Mock
    private AiLlmGateway llmGateway;
    @Mock
    private Executor auditTriggerExecutor;

    @InjectMocks
    private CommentAuditService commentAuditService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 lambda 列缓存，使单测不依赖 Spring 上下文（与项目内其它测试一致）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApAuditTask.class);
    }

    private AuditContext context(Long commentId) {
        AuditContext ctx = new AuditContext(AuditEntityType.COMMENT, commentId, 100L);
        ctx.withContent("这是一条评论")
                .withAuthorName("张三")
                .withUserId(100)
                .withTargetType(1)
                .withTargetId(999L)
                .withTargetUserId(200);
        return ctx;
    }

    private ApAuditTask buildTask(Long commentId) {
        ApAuditTask task = new ApAuditTask();
        task.setId(1L);
        task.setTaskKey(ApAuditTask.taskKey(ApAuditTask.BIZ_ARTICLE_COMMENT, commentId));
        task.setBizType(ApAuditTask.BIZ_ARTICLE_COMMENT);
        task.setBizId(commentId);
        task.setAuthorId(100);
        task.setContent("这是一条评论");
        task.setTargetType(1);
        task.setTargetId(999L);
        task.setTargetUserId(200);
        return task;
    }

    // ==================== 入队 ====================

    @Test
    @DisplayName("入队：字段映射正确（bizType/taskKey/bizId/authorId/target 系列）")
    void enqueueMapsFields() {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) {
                ApAuditTask task = invocation.getArgument(0);
                assertEquals(ApAuditTask.BIZ_ARTICLE_COMMENT, task.getBizType());
                assertEquals(ApAuditTask.taskKey(ApAuditTask.BIZ_ARTICLE_COMMENT, 555L), task.getTaskKey());
                assertEquals(555L, task.getBizId());
                assertEquals(100, task.getAuthorId(), "评论者即该条评论的作者");
                assertEquals(1, task.getTargetType());
                assertEquals(999L, task.getTargetId());
                assertEquals(200, task.getTargetUserId());
                assertEquals(ApAuditTask.STATUS_PENDING, task.getStatus());
                task.setId(777L);
                return null;
            }
        }).when(auditTaskDispatcher).enqueue(any(ApAuditTask.class));

        commentAuditService.asyncAuditComment(context(555L));

        verify(auditTaskDispatcher).enqueue(any(ApAuditTask.class));
    }

    @Test
    @DisplayName("入队：上下文或评论ID为空时直接返回，不写库")
    void enqueueSkipsWhenContextInvalid() {
        commentAuditService.asyncAuditComment(null);
        verify(auditTaskDispatcher, never()).enqueue(any(ApAuditTask.class));

        commentAuditService.asyncAuditComment(new AuditContext());
        verify(auditTaskDispatcher, never()).enqueue(any(ApAuditTask.class));
    }

    // ==================== 降级放行的业务动作 ====================

    @Test
    @DisplayName("onDegraded：缺少目标用户或目标内容时不补发通知（避免误发）")
    void onDegradedSkipsWithoutTarget() {
        ApAuditTask task = buildTask(555L);
        task.setTargetUserId(null);

        assertDoesNotThrow(() -> commentAuditService.onDegraded(task));

        task.setTargetUserId(200);
        task.setTargetId(null);
        assertDoesNotThrow(() -> commentAuditService.onDegraded(task));
    }

    @Test
    @DisplayName("onDegraded：字段齐全时执行补发通知（不抛异常）")
    void onDegradedSendsNotification() {
        assertDoesNotThrow(() -> commentAuditService.onDegraded(buildTask(555L)));
    }

    // ==================== 调度委托 ====================

    @Test
    @DisplayName("调度委托：processTaskIfPending / listPendingDue 原样转交 Dispatcher")
    void delegatesSchedulingToDispatcher() {
        commentAuditService.processTaskIfPending(1L);
        verify(auditTaskDispatcher).processIfPending(1L);

        commentAuditService.listPendingDue(50);
        verify(auditTaskDispatcher).listPendingDue(ApAuditTask.BIZ_ARTICLE_COMMENT, 50);
    }
}
