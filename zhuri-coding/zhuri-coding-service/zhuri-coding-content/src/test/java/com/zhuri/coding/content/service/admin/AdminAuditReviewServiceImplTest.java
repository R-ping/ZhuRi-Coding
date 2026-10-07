package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.audit.ApAuditTaskMapper;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.content.service.admin.impl.AdminAuditReviewServiceImpl;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminAuditReviewVO;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 审核复核队列（违规任务线）单测。
 *
 * <p>盯住"接口返回成功、实际却不是那个样"的几类失效：
 * <ol>
 *   <li><b>队列只出"机器已处置、人还没看过"的任务</b>（status=3 且 review_status=0），
 *       先违规先复核；恢复可行性按业务行现查（历史物理删除的任务如实标注不可恢复）；</li>
 *   <li><b>复核权先占、内容后动</b>：CAS 抢 review_status 在前，输家不能动业务表 ——
 *       否则两个运营同时放行会双恢复、双通知；</li>
 *   <li><b>恢复失败整体失败</b>：内容行不存在（软删改造前的历史物理删除）抛异常记失败审计，
 *       绝不留"任务显示已放行、内容还删着"的半截态；</li>
 *   <li><b>维持违规绝不动内容</b>：uphold 只改任务标记，恢复动作一行都不执行。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审核复核队列（AdminAuditReviewServiceImpl）")
class AdminAuditReviewServiceImplTest {

    private static final long TASK_ID = 41L;
    private static final long BIZ_ID = 9001L;
    private static final long TARGET_ID = 1001L;
    private static final Integer AUTHOR_ID = 7;
    private static final String REASON = "人工通读内容，属正常技术讨论，机器误判";

    @Mock
    private ApAuditTaskMapper auditTaskMapper;

    @Mock
    private ApCommentMapper apCommentMapper;

    @Mock
    private ApPinsCommentMapper apPinsCommentMapper;

    @Mock
    private ApPinsMapper apPinsMapper;

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Mock
    private AdminAuditRecorder auditRecorder;

    @Mock
    private com.zhuri.coding.apis.notification.INotificationClient notificationClient;

    @InjectMocks
    private AdminAuditReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器靠实体元信息解析列名。单测没有 MyBatis 会话，必须先手动初始化
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApAuditTask.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApComment.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPinsComment.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPins.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBehaviorRecord.class);
    }

    // ==================== 造数 ====================

    private static ApAuditTask task(long id, String bizType, long bizId) {
        ApAuditTask t = new ApAuditTask();
        t.setId(id);
        t.setTaskKey(ApAuditTask.taskKey(bizType, bizId));
        t.setBizType(bizType);
        t.setBizId(bizId);
        t.setAuthorId(AUTHOR_ID);
        t.setAuthorName("评论者甲");
        t.setContent("这条内容被机器判违规了，但其实是正常的技术讨论");
        t.setViolationReason("色情低俗: 内容含违规表述");
        t.setStatus(ApAuditTask.STATUS_VIOLATION);
        t.setReviewStatus(ApAuditTask.REVIEW_PENDING);
        t.setAuditTime(new Date());
        t.setTargetType(1);
        t.setTargetId(TARGET_ID);
        return t;
    }

    private static ApComment softDeletedComment() {
        ApComment c = new ApComment();
        c.setId(BIZ_ID);
        c.setArticleId(TARGET_ID);
        c.setUserId(AUTHOR_ID);
        c.setContent("这条内容被机器判违规了，但其实是正常的技术讨论");
        c.setIsHidden(0);
        c.setIsDeleted(1);
        return c;
    }

    private ApAdminAuditLog captureSuccessAudit() {
        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(captor.capture());
        return captor.getValue();
    }

    private static void assertInvalid(ResponseResult result, String expectedFragment) {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode(), result.getMessage());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains(expectedFragment),
            "错误信息要能直接照着改：" + result.getMessage());
    }

    // ==================== 队列 ====================

    @Nested
    @DisplayName("队列")
    class Queue {

        @Test
        @DisplayName("只出违规且未复核的任务，先违规先复核（audit_time 升序、id 兜底）；分页参数原样下发")
        @SuppressWarnings("unchecked")
        void onlyViolatedPendingOrdered() {
            AtomicReference<Page<ApAuditTask>> pageArg = new AtomicReference<>();
            AtomicReference<Wrapper<ApAuditTask>> wrapperArg = new AtomicReference<>();
            Page<ApAuditTask> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of());
            when(auditTaskMapper.selectPage(any(), any())).thenAnswer(inv -> {
                pageArg.set(inv.getArgument(0));
                wrapperArg.set(inv.getArgument(1));
                return resultPage;
            });

            ResponseResult result = service.page(null, 2, 10);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            assertEquals(2, pageArg.get().getCurrent());
            assertEquals(10, pageArg.get().getSize());

            LambdaQueryWrapper<ApAuditTask> wrapper = (LambdaQueryWrapper<ApAuditTask>) wrapperArg.get();
            String sql = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApAuditTask.STATUS_VIOLATION),
                "只出违规任务：" + params);
            assertTrue(params.containsValue(ApAuditTask.REVIEW_PENDING),
                "只出未复核任务（已复核的退出队列）：" + params);
            assertTrue(sql.contains("ORDER BY"), sql);
            assertTrue(sql.contains("audit_time ASC"), "先违规先复核：" + sql);
            assertTrue(sql.contains("id ASC"), "同刻任务按 id 兜底，翻页才不抖动：" + sql);
        }

        @Test
        @DisplayName("业务类型拼错要报错而不是返回空列表：空列表会让运营以为'这类任务确实没有'")
        void rejectsUnknownBizType() {
            assertInvalid(service.page("comment", 1, 20), "无法识别的业务类型");
            verifyNoInteractions(auditTaskMapper);
        }

        @Test
        @DisplayName("恢复可行性按业务行现查：软删行可恢复；历史物理删除/状态已变如实标注不可恢复")
        @SuppressWarnings("unchecked")
        void assemblesRestorableFlags() {
            ApAuditTask commentTask = task(41L, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID);
            ApAuditTask goneTask = task(42L, ApAuditTask.BIZ_PINS_COMMENT, 9002L);
            ApAuditTask pinsTask = task(43L, ApAuditTask.BIZ_PINS, 9003L);
            Page<ApAuditTask> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of(commentTask, goneTask, pinsTask));
            resultPage.setTotal(3);
            when(auditTaskMapper.selectPage(any(), any())).thenReturn(resultPage);

            when(apCommentMapper.selectBatchIds(any())).thenReturn(List.of(softDeletedComment()));
            when(apPinsCommentMapper.selectBatchIds(any())).thenReturn(List.of()); // 行不在 = 历史物理删除
            ApPins pins = new ApPins();
            pins.setId(9003L);
            pins.setStatus(ApPins.Status.FAIL.getCode());
            pins.setContent("沸点内容");
            pins.setAuthorId(AUTHOR_ID.longValue()); // ApPins.authorId 是 Long，与 ApAuditTask(Integer) 不一致
            when(apPinsMapper.selectBatchIds(any())).thenReturn(List.of(pins));

            ResponseResult result = service.page(null, 1, 20);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            @SuppressWarnings("unchecked")
            List<AdminAuditReviewVO> list = (List<AdminAuditReviewVO>) data.get("list");
            assertEquals(3, list.size());

            AdminAuditReviewVO voComment = list.get(0);
            assertEquals("文章评论", voComment.getBizTypeDesc());
            assertEquals("色情低俗: 内容含违规表述", voComment.getViolationReason(),
                "机器判定原因要进队列，运营先看'为什么判违规'再读内容");
            assertTrue(voComment.getContentExcerpt().endsWith("讨论"),
                "短内容原样透出，不追加省略号");
            assertTrue(voComment.isRestorable(), "软删行还在：可恢复");

            AdminAuditReviewVO voGone = list.get(1);
            assertFalse(voGone.isRestorable(), "历史物理删除的行不在：不可恢复");
            assertTrue(voGone.getRestorableDesc().contains("历史物理删除"),
                "要告诉运营为什么不能恢复：" + voGone.getRestorableDesc());

            AdminAuditReviewVO voPins = list.get(2);
            assertEquals("沸点", voPins.getBizTypeDesc());
            assertTrue(voPins.isRestorable(), "沸点还在审核失败态：可恢复");

            // 三类业务各查一次，不逐行打库
            verify(apCommentMapper).selectBatchIds(any());
            verify(apPinsCommentMapper).selectBatchIds(any());
            verify(apPinsMapper).selectBatchIds(any());
        }

        @Test
        @DisplayName("空队列不触发任何业务回查（selectBatchIds 收到空集合会拼出非法 SQL）")
        void emptyQueueSkipsBusinessQueries() {
            Page<ApAuditTask> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of());
            when(auditTaskMapper.selectPage(any(), any())).thenReturn(resultPage);

            ResponseResult result = service.page(null, 1, 20);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            verifyNoInteractions(apCommentMapper, apPinsCommentMapper, apPinsMapper, behaviorRecordMapper);
        }
    }

    // ==================== 复核放行 ====================

    @Nested
    @DisplayName("复核放行")
    class Restore {

        @Test
        @DisplayName("放行评论：CAS 抢复核权（WHERE status=3 AND review_status=0）→ 软删翻回 → 行为记录 → 补发通知")
        @SuppressWarnings("unchecked")
        void restoresArticleCommentEndToEnd() {
            when(auditTaskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID));
            when(auditTaskMapper.update(isNull(), any())).thenReturn(1);
            when(apCommentMapper.selectById(BIZ_ID)).thenReturn(softDeletedComment());
            when(apCommentMapper.update(isNull(), any())).thenReturn(1);
            when(behaviorRecordMapper.selectCount(any())).thenReturn(0L);
            when(behaviorRecordMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.restore(TASK_ID, "  " + REASON + "  ");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());

            // 1) 复核权 CAS：WHERE 必须带 status=3 与 review_status=0 双比对（先渲染 sqlSegment 再断言参数）
            ArgumentCaptor<Wrapper<ApAuditTask>> taskCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(auditTaskMapper).update(isNull(), taskCaptor.capture());
            LambdaUpdateWrapper<ApAuditTask> taskWrapper =
                (LambdaUpdateWrapper<ApAuditTask>) taskCaptor.getValue();
            String taskWhere = taskWrapper.getSqlSegment();
            Map<String, Object> taskParams = taskWrapper.getParamNameValuePairs();
            assertTrue(taskParams.containsValue(ApAuditTask.REVIEW_RESTORED), "目标：复核放行：" + taskParams);
            assertTrue(taskParams.containsValue(ApAuditTask.STATUS_VIOLATION),
                "WHERE 必须锚定违规任务：" + taskWhere + " / " + taskParams);
            assertTrue(taskParams.containsValue(ApAuditTask.REVIEW_PENDING),
                "WHERE 必须带未复核比对，挡并发复核：" + taskWhere + " / " + taskParams);

            // 2) 内容恢复 CAS：WHERE 带旧值 is_deleted=1，挡住与其它恢复动作的竞态
            ArgumentCaptor<Wrapper<ApComment>> commentCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(apCommentMapper).update(isNull(), commentCaptor.capture());
            LambdaUpdateWrapper<ApComment> commentWrapper =
                (LambdaUpdateWrapper<ApComment>) commentCaptor.getValue();
            String commentWhere = commentWrapper.getSqlSegment();
            Map<String, Object> commentParams = commentWrapper.getParamNameValuePairs();
            assertTrue(commentParams.containsValue(0), "SET is_deleted=0（恢复可见）：" + commentParams);
            assertTrue(commentParams.containsValue(1),
                "WHERE 必须带旧值比对（CAS 挡并发恢复）：" + commentWhere + " / " + commentParams);

            // 3) 行为记录恢复（无有效记录时置回）
            verify(behaviorRecordMapper).selectCount(any());
            verify(behaviorRecordMapper).update(isNull(), any());

            // 4) 补发恢复通知（旧违规通知无法撤回，恢复要补正向通知）
            verify(notificationClient).createNotification(any());

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(ApAdminAuditLog.MODULE_CONTENT, audit.getModule());
            assertEquals(AdminAuditReviewService.ACTION_RESTORE, audit.getAction());
            assertEquals(AdminAuditReviewService.TARGET_AUDIT_TASK, audit.getTargetType());
            assertEquals(String.valueOf(TASK_ID), audit.getTargetId());
            assertEquals(REASON, audit.getReason(), "理由 trim 后落审计");
            assertTrue(audit.getDetail().contains("复核放行"), audit.getDetail());
        }

        @Test
        @DisplayName("内容行不存在（历史物理删除）→ 异常 + 失败审计，绝不能留下'已放行、内容还删着'的半截态")
        void missingCommentRowFailsLoudly() {
            when(auditTaskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID));
            when(auditTaskMapper.update(isNull(), any())).thenReturn(1);
            when(apCommentMapper.selectById(BIZ_ID)).thenReturn(null);

            assertThrows(IllegalStateException.class, () -> service.restore(TASK_ID, REASON));

            verify(auditRecorder).recordFailure(any(ApAdminAuditLog.class), contains("历史物理删除"));
            verify(auditRecorder, never()).recordSuccess(any());
            verifyNoInteractions(notificationClient);
        }

        @Test
        @DisplayName("内容已不处于软删态（如已被其它路径恢复）→ 异常，不重复恢复")
        void commentNotDeletedRejected() {
            when(auditTaskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID));
            when(auditTaskMapper.update(isNull(), any())).thenReturn(1);
            ApComment visible = softDeletedComment();
            visible.setIsDeleted(0);
            when(apCommentMapper.selectById(BIZ_ID)).thenReturn(visible);

            assertThrows(IllegalStateException.class, () -> service.restore(TASK_ID, REASON));

            verify(apCommentMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("已复核的任务（如已维持违规）→ 明确报'已复核'，不静默重复处理")
        void alreadyReviewedRejected() {
            ApAuditTask reviewed = task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID);
            reviewed.setReviewStatus(ApAuditTask.REVIEW_UPHELD);
            when(auditTaskMapper.selectById(TASK_ID)).thenReturn(reviewed);

            assertInvalid(service.restore(TASK_ID, REASON), "已复核");

            verify(auditTaskMapper, never()).update(any(), any());
            verifyNoInteractions(apCommentMapper, auditRecorder);
        }

        @Test
        @DisplayName("CAS 命中 0 行（并发复核）→ 报'请刷新后重试'，输家绝不能动业务表")
        void concurrentClaimRejected() {
            when(auditTaskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID));
            when(auditTaskMapper.update(isNull(), any())).thenReturn(0);

            assertInvalid(service.restore(TASK_ID, REASON), "刷新后重试");

            verifyNoInteractions(apCommentMapper, notificationClient);
            verify(auditRecorder, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("放行沸点：CAS 把审核失败态翻回已发布，并补发通知")
        @SuppressWarnings("unchecked")
        void restoresPinsFromFailToPublished() {
            ApAuditTask pinsTask = task(TASK_ID, ApAuditTask.BIZ_PINS, BIZ_ID);
            pinsTask.setTargetType(null); // 沸点本体任务没有 target 系列
            when(auditTaskMapper.selectById(TASK_ID)).thenReturn(pinsTask);
            when(auditTaskMapper.update(isNull(), any())).thenReturn(1);
            ApPins pins = new ApPins();
            pins.setId(BIZ_ID);
            pins.setStatus(ApPins.Status.FAIL.getCode());
            pins.setContent("沸点内容");
            pins.setAuthorId(AUTHOR_ID.longValue()); // ApPins.authorId 是 Long，与 ApAuditTask(Integer) 不一致
            when(apPinsMapper.selectById(BIZ_ID)).thenReturn(pins);
            when(apPinsMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.restore(TASK_ID, REASON);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());

            ArgumentCaptor<Wrapper<ApPins>> pinsCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(apPinsMapper).update(isNull(), pinsCaptor.capture());
            LambdaUpdateWrapper<ApPins> pinsWrapper = (LambdaUpdateWrapper<ApPins>) pinsCaptor.getValue();
            String where = pinsWrapper.getSqlSegment();
            Map<String, Object> params = pinsWrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApPins.Status.PUBLISHED.getCode()),
                "SET 状态=已发布：" + params);
            assertTrue(params.containsValue(ApPins.Status.FAIL.getCode()),
                "WHERE 必须带旧值（仅审核失败态可翻回）：" + where + " / " + params);
            // 沸点违规没有撤销过行为记录，恢复也不需要
            verifyNoInteractions(behaviorRecordMapper);
        }

        @Test
        @DisplayName("沸点已不在审核失败态（如已被下架）→ 异常，不放行")
        void pinsNotInFailRejected() {
            ApAuditTask pinsTask = task(TASK_ID, ApAuditTask.BIZ_PINS, BIZ_ID);
            when(auditTaskMapper.selectById(TASK_ID)).thenReturn(pinsTask);
            when(auditTaskMapper.update(isNull(), any())).thenReturn(1);
            ApPins takenDown = new ApPins();
            takenDown.setId(BIZ_ID);
            takenDown.setStatus(ApPins.Status.DRAFT.getCode());
            takenDown.setContent("沸点内容");
            when(apPinsMapper.selectById(BIZ_ID)).thenReturn(takenDown);

            assertThrows(IllegalStateException.class, () -> service.restore(TASK_ID, REASON));

            verify(apPinsMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("理由缺失 / 超长 → 参数错误，不碰库、不留审计")
        void rejectsInvalidReason() {
            assertInvalid(service.restore(TASK_ID, "   "), "操作理由");
            assertInvalid(service.uphold(TASK_ID, null), "操作理由");
            assertInvalid(service.uphold(TASK_ID, "水".repeat(501)), "500");
            verifyNoInteractions(auditTaskMapper, auditRecorder);
        }
    }

    // ==================== 维持违规 ====================

    @Nested
    @DisplayName("维持违规")
    class Uphold {

        @Test
        @DisplayName("维持违规：只标记任务退出队列，内容、行为记录、通知一概不动")
        @SuppressWarnings("unchecked")
        void upholdOnlyMarksTask() {
            when(auditTaskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID));
            when(auditTaskMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.uphold(TASK_ID, "典型引流话术，维持机器判定");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<Wrapper<ApAuditTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(auditTaskMapper).update(isNull(), captor.capture());
            LambdaUpdateWrapper<ApAuditTask> wrapper = (LambdaUpdateWrapper<ApAuditTask>) captor.getValue();
            // 先渲染 sqlSegment，WHERE 的 eq 参数才会注册进 paramNameValuePairs（惰性求值）
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApAuditTask.REVIEW_UPHELD), "目标：维持违规：" + params);

            verifyNoInteractions(apCommentMapper, apPinsCommentMapper, apPinsMapper,
                behaviorRecordMapper, notificationClient);

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(AdminAuditReviewService.ACTION_UPHOLD, audit.getAction());
            assertTrue(audit.getDetail().contains("维持违规"), audit.getDetail());
        }

        @Test
        @DisplayName("非违规任务不能复核（异常态防御：只有违规才有'维持/恢复'两种去向）")
        void taskMustBeViolation() {
            ApAuditTask passed = task(TASK_ID, ApAuditTask.BIZ_ARTICLE_COMMENT, BIZ_ID);
            passed.setStatus(ApAuditTask.STATUS_PASSED);
            when(auditTaskMapper.selectById(TASK_ID)).thenReturn(passed);

            assertInvalid(service.uphold(TASK_ID, REASON), "只有违规任务");
            assertInvalid(service.restore(TASK_ID, REASON), "只有违规任务");

            verify(auditTaskMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("任务不存在 → 数据不存在错误，不更新、不留审计")
        void missingTaskRejected() {
            when(auditTaskMapper.selectById(TASK_ID)).thenReturn(null);

            ResponseResult result = service.uphold(TASK_ID, REASON);

            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
            verify(auditTaskMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }
    }
}
