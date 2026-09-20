package com.heima.content.service.comment.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.apis.notification.INotificationClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.heima.content.mapper.comment.ApCommentAuditTaskMapper;
import com.heima.content.mapper.comment.ApCommentMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.content.service.article.BailianAiService;
import com.heima.content.service.article.impl.AbstractAuditService;
import com.heima.model.audit.AuditServiceUnavailableException;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.audit.pojos.ApCommentAuditTask;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.comment.pojos.ApComment;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommentAuditService 单元测试
 *
 * 核心安全诉求"先展示后审核"窗口期的可靠性：
 * 1. 审核任务必须落库（ap_comment_audit_task），服务重启后由补偿任务重拉，不丢失；
 * 2. 同一条评论幂等入队（DuplicateKeyException 忽略重复）；
 * 3. 处理前 CAS 抢占（PENDING→PROCESSING），避免进程内与补偿执行重复审核；
 * 4. 通过回调给作者发通知、违规回调软删评论并联系统通知，均防空态越权。
 */
class CommentAuditServiceTest {

    @Mock
    private ApCommentMapper apCommentMapper;
    @Mock
    private BailianAiService bailianAiService;
    @Mock
    private ApCommentAuditTaskMapper auditTaskMapper;
    @Mock
    private INotificationClient notificationClient;
    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private CommentAuditService commentAuditService;

    private final Long commentId = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 将 BailianAiService mock 注入父类 AbstractAuditService 私有字段，
        // 以便显式模拟"AI 审核服务不可用"触发 fail-closed 与降级通过
        try {
            Field f = AbstractAuditService.class.getDeclaredField("bailianAiService");
            f.setAccessible(true);
            f.set(commentAuditService, bailianAiService);
        } catch (Exception e) {
            throw new RuntimeException("注入 BailianAiService 失败", e);
        }
        // 预热 MybatisPlus 实体表元数据与 lambda 列缓存，使单测不依赖 Spring 上下文或测试执行顺序
        // （集成测试若在共享 JVM 中先加载 Spring 会自动注册缓存；本单测须自足，CI 无库也能稳定运行）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCommentAuditTask.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBehaviorRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApComment.class);
    }

    private AuditContext context() {
        AuditContext ctx = new AuditContext(AuditEntityType.COMMENT, commentId, 100L);
        ctx.withContent("正常评论内容")
            .withUserId(100)
            .withTargetType(1)
            .withTargetId(99L)
            .withTargetUserId(200);
        return ctx;
    }

    // ==================== asyncAuditComment - 可靠入队 ====================

    @Test
    @DisplayName("asyncAuditComment - 上下文为空直接返回不落库")
    void testAsyncAuditNullContext() {
        commentAuditService.asyncAuditComment(null);
        verify(auditTaskMapper, never()).insert(any(ApCommentAuditTask.class));
    }

    @Test
    @DisplayName("asyncAuditComment - 评论ID为空直接返回不落库")
    void testAsyncAuditNoEntityId() {
        AuditContext ctx = new AuditContext(AuditEntityType.COMMENT, null, 100L);
        commentAuditService.asyncAuditComment(ctx);
        verify(auditTaskMapper, never()).insert(any(ApCommentAuditTask.class));
    }

    @Test
    @DisplayName("asyncAuditComment - 正常入队持久化任务")
    void testAsyncAuditEnqueue() {
        commentAuditService.asyncAuditComment(context());
        verify(auditTaskMapper).insert(any(ApCommentAuditTask.class));
    }

    @Test
    @DisplayName("asyncAuditComment - 重复入队(DuplicateKey)忽略不中断")
    void testAsyncAuditDuplicateIgnored() {
        when(auditTaskMapper.insert(any(ApCommentAuditTask.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        // 不应抛异常
        commentAuditService.asyncAuditComment(context());
    }

    // ==================== processTaskIfPending - CAS 抢占 ====================

    @Test
    @DisplayName("processTaskIfPending - 任务ID为空直接返回")
    void testProcessTaskNullId() {
        commentAuditService.processTaskIfPending(null);
        verify(auditTaskMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("processTaskIfPending - CAS抢占失败(已被处理)不重复执行")
    void testProcessTaskCasFail() {
        when(auditTaskMapper.update(any(), any())).thenReturn(0);
        commentAuditService.processTaskIfPending(5L);
        verify(auditTaskMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("processTaskIfPending - 抢占成功但任务已删返回")
    void testProcessTaskTaskMissing() {
        when(auditTaskMapper.update(any(), any())).thenReturn(1);
        when(auditTaskMapper.selectById(5L)).thenReturn(null);
        commentAuditService.processTaskIfPending(5L);
        verify(auditTaskMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("processTaskIfPending - 抢占成功审核通过标记PASSED")
    void testProcessTaskPassed() {
        when(auditTaskMapper.update(any(), any())).thenReturn(1);
        ApCommentAuditTask task = new ApCommentAuditTask();
        task.setId(5L);
        task.setCommentId(commentId);
        task.setContent("");
        task.setCommenterId(100);
        task.setCommenterName("张三");
        task.setTargetType(1);
        when(auditTaskMapper.selectById(5L)).thenReturn(task);

        commentAuditService.processTaskIfPending(5L);

        // CAS 一次 + markDone 一次
        verify(auditTaskMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("processTaskIfPending - 重试超限降级通过时补发评论通知")
    void testProcessTaskDegradeSendsNotification() {
        when(auditTaskMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        ApCommentAuditTask task = new ApCommentAuditTask();
        task.setId(5L);
        task.setCommentId(commentId);
        task.setContent("非空内容，触发AI审核");
        task.setCommenterId(100);
        task.setCommenterName("张三");
        task.setTargetType(1);
        task.setTargetId(99L);
        task.setTargetUserId(200);
        task.setRetryCount(ApCommentAuditTask.MAX_RETRY); // 已超重试上限，下次即降级通过
        when(auditTaskMapper.selectById(5L)).thenReturn(task);
        // AI 审核服务不可用（fail-closed 抛异常 → 重试超限 → 降级通过）
        when(bailianAiService.checkViolation(any(), any(), any()))
            .thenThrow(new AuditServiceUnavailableException("ai down"));

        commentAuditService.processTaskIfPending(5L);

        // CAS 抢占一次 + 降级通过(DEGRADED_PASSED)一次
        verify(auditTaskMapper, times(2)).update(any(), any());
        // 降级通过即评论可见 → 向作者补发一条"评论通知"(type=1)
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient, times(1)).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals(200L, params.get("userId")); // 目标作者(targetUserId=200)
        assertEquals(1, params.get("type"));      // 评论通知
    }

    // ==================== handlePassed ====================

    @Test
    @DisplayName("handlePassed - 评论不存在仅告警")
    void testHandlePassedCommentMissing() {
        when(apCommentMapper.selectById(commentId)).thenReturn(null);
        commentAuditService.handlePassed(context());
        verify(notificationClient, never()).createNotification(any());
    }

    @Test
    @DisplayName("handlePassed - 评论存在且目标作者非空：仅过审后发一条评论通知")
    void testHandlePassedSendsNotification() {
        ApComment comment = new ApComment();
        comment.setId(commentId);
        comment.setContent("正常评论内容");
        comment.setUserId(100);
        when(apCommentMapper.selectById(commentId)).thenReturn(comment);

        commentAuditService.handlePassed(context());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient, times(1)).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        // 断言语义：仅审核通过后，向目标作者恰好发送一条"评论通知"(type=1)
        assertEquals(200L, params.get("userId"));   // 目标作者(targetUserId=200)
        assertEquals(1, params.get("type"));        // 1 = 评论通知
        assertEquals("99", params.get("sourceId")); // 被评论的目标内容
    }

    // ==================== handleFailed ====================

    @Test
    @DisplayName("handleFailed - 评论不存在直接返回")
    void testHandleFailedCommentMissing() {
        when(apCommentMapper.selectById(commentId)).thenReturn(null);
        commentAuditService.handleFailed(context(), "违规");
        verify(apCommentMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("handleFailed - 违规软删评论并撤销行为记录")
    void testHandleFailedDeleteAndRevoke() {
        ApComment comment = new ApComment();
        comment.setId(commentId);
        comment.setContent("违规内容");
        comment.setUserId(100);
        when(apCommentMapper.selectById(commentId)).thenReturn(comment);

        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setId(7L);
        record.setStatus(1);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(record);

        commentAuditService.handleFailed(context(), "违规");

        verify(apCommentMapper).deleteById(commentId);
        assertEquals(0, record.getStatus());
        verify(behaviorRecordMapper).updateById(record);
        verify(notificationClient).createNotification(any());
    }

    @Test
    @DisplayName("handleFailed - 审核违规不发“评论通知”，仅发违规系统通知(type=4)")
    void testHandleFailedNoCommentNotification() {
        ApComment comment = new ApComment();
        comment.setId(commentId);
        comment.setContent("违规内容");
        comment.setUserId(100);
        when(apCommentMapper.selectById(commentId)).thenReturn(comment);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null); // 无行为记录

        commentAuditService.handleFailed(context(), "违规");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient, times(1)).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        // 断言语义：评论未过审绝不发"评论通知"(type=1)，只发违规系统通知(type=4)
        assertEquals(4, params.get("type"));
    }

    // ==================== listPendingDue ====================

    @Test
    @DisplayName("listPendingDue - 返回到期待审核任务")
    void testListPendingDue() {
        when(auditTaskMapper.selectList(any()))
                .thenReturn(Collections.singletonList(new ApCommentAuditTask()));
        List<ApCommentAuditTask> result = commentAuditService.listPendingDue(20);
        assertEquals(1, result.size());
    }
}