package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.service.admin.impl.AdminContentFoldServiceImpl;
import com.zhuri.coding.model.admin.AdminContentType;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminCommentVO;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 运营侧「内容折叠 / 解除折叠」单测。
 *
 * <p>盯住三件事：
 * <ul>
 *   <li><b>幂等闸口是条件更新</b>：折叠的 WHERE 里必须带 {@code is_hidden}，
 *       否则两个运营同时点会各"成功"一次，审计上却看不出是同一件事；</li>
 *   <li><b>"已折叠"要报错，不能静默成功</b>：静默成功会让运营以为自己刚折了一条新评论；</li>
 *   <li><b>评论者必须被通知</b>：折叠对作者是"评论还在但没人看得见"，不通知等于内容被吞。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营侧内容折叠（AdminContentFoldServiceImpl）")
class AdminContentFoldServiceImplTest {

    private static final Long COMMENT_ID = 7001L;
    private static final Long ARTICLE_ID = 9100L;
    private static final Long PINS_ID = 9200L;
    private static final Integer AUTHOR_ID = 3201;

    @Mock
    private ApCommentMapper commentMapper;
    @Mock
    private ApPinsCommentMapper pinsCommentMapper;
    @Mock
    private AdminContentNotifier notifier;
    @Mock
    private AdminAuditRecorder auditRecorder;

    @InjectMocks
    private AdminContentFoldServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器要靠实体元信息解析列名，单测没有 MyBatis 会话，必须先手动初始化
        // （本仓既有测试同此做法，缺了会抛 "can not find lambda cache for this entity"）
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApComment.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPinsComment.class);
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表·文章评论：带出正文与所属文章（运营只看 id 判不了要不要折）")
    void pageForArticleComment() {
        Page<ApComment> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of(normalComment()));
        dbPage.setTotal(1);
        when(commentMapper.selectPage(any(), any())).thenReturn(dbPage);

        ResponseResult result = service.page(AdminContentType.COMMENT, 1, 1, 20);

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1L, data.get("total"));
        @SuppressWarnings("unchecked")
        List<AdminCommentVO> list = (List<AdminCommentVO>) data.get("list");
        assertEquals(1, list.size());
        AdminCommentVO vo = list.get(0);
        assertEquals("COMMENT", vo.getTargetType());
        assertEquals(ARTICLE_ID, vo.getOwnerId(), "文章评论的所属主体是 articleId");
        assertEquals(AUTHOR_ID, vo.getAuthorId());
        assertEquals("这句话说得不对", vo.getContent());
        assertEquals(1, vo.getHidden().intValue());
    }

    @Test
    @DisplayName("列表·沸点评论：所属主体取 pinsId，且不碰文章评论表")
    void pageForPinsComment() {
        Page<ApPinsComment> dbPage = new Page<>(1, 20);
        ApPinsComment c = new ApPinsComment();
        c.setId(COMMENT_ID);
        c.setPinsId(PINS_ID);
        c.setUserId(AUTHOR_ID);
        c.setUserName("路人甲");
        c.setContent("软广嫌疑");
        c.setIsHidden(1);
        c.setCreatedTime(new Date());
        dbPage.setRecords(List.of(c));
        dbPage.setTotal(1);
        when(pinsCommentMapper.selectPage(any(), any())).thenReturn(dbPage);

        ResponseResult result = service.page(AdminContentType.PINS_COMMENT, 1, 1, 20);

        @SuppressWarnings("unchecked")
        List<AdminCommentVO> list = (List<AdminCommentVO>) ((Map<String, Object>) result.getData()).get("list");
        assertEquals("PINS_COMMENT", list.get(0).getTargetType());
        assertEquals(PINS_ID, list.get(0).getOwnerId(), "沸点评论的所属主体是 pinsId");
        verify(commentMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：页大小越界（0 / 过大）回落到默认 20")
    void pageClampsSize() {
        Page<ApComment> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of());
        dbPage.setTotal(0);
        when(commentMapper.selectPage(any(), any())).thenReturn(dbPage);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) service.page(AdminContentType.COMMENT, null, 0, 999).getData();

        assertEquals(1, data.get("page"));
        assertEquals(20, data.get("size"));
    }

    // ==================== 折叠 ====================

    @Test
    @DisplayName("折叠：置 is_hidden=1 + 通知评论者 + 记成功审计")
    void foldSucceeds() {
        givenCommentExists();
        when(commentMapper.update(isNull(), any())).thenReturn(1);

        ResponseResult result = service.fold(AdminContentType.COMMENT, COMMENT_ID, "含人身攻击");

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, data.get("hidden"));

        // 幂等闸口：WHERE 必须同时带 id 与 is_hidden，否则并发下两边都能改到行
        String sql = captureCommentUpdateSql();
        assertTrue(sql.contains("is_hidden"), "条件更新必须限定当前状态，否则并发折叠会双写：" + sql);

        verify(notifier).notifyCommentAuthor("COMMENT", COMMENT_ID, true, "含人身攻击");

        ApAdminAuditLog audit = captureSuccessAudit();
        assertEquals(ApAdminAuditLog.MODULE_CONTENT, audit.getModule());
        assertEquals(AdminContentFoldService.ACTION_FOLD, audit.getAction());
        assertEquals("COMMENT", audit.getTargetType());
        assertEquals(String.valueOf(COMMENT_ID), audit.getTargetId());
        assertTrue(audit.getDetail().contains("0 -> 1"), "变更摘要要能看出从哪个状态改到哪个状态");
    }

    @Test
    @DisplayName("折叠·沸点评论：更新落在 ap_pins_comment，不改文章评论")
    void foldPinsComment() {
        when(pinsCommentMapper.selectCount(any())).thenReturn(1L);
        when(pinsCommentMapper.update(isNull(), any())).thenReturn(1);

        ResponseResult result = service.fold(AdminContentType.PINS_COMMENT, COMMENT_ID, "疑似软广");

        assertEquals(200, result.getCode().intValue());
        verify(commentMapper, never()).update(any(), any());
        verify(notifier).notifyCommentAuthor("PINS_COMMENT", COMMENT_ID, true, "疑似软广");
        assertEquals("PINS_COMMENT", captureSuccessAudit().getTargetType());
    }

    @Test
    @DisplayName("折叠·评论不存在 → 报错，零写入、零通知、零成功审计")
    void foldMissingComment() {
        when(commentMapper.selectCount(any())).thenReturn(0L);

        ResponseResult result = service.fold(AdminContentType.COMMENT, COMMENT_ID, "含人身攻击");

        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode().intValue());
        verify(commentMapper, never()).update(any(), any());
        verify(notifier, never()).notifyCommentAuthor(any(), any(), eq(true), any());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("折叠·已经是折叠态 → 明确报错，不静默成功（否则运营以为刚折了条新评论）")
    void foldAlreadyHiddenIsRejected() {
        givenCommentExists();
        when(commentMapper.update(isNull(), any())).thenReturn(0);

        ResponseResult result = service.fold(AdminContentType.COMMENT, COMMENT_ID, "含人身攻击");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("已是折叠状态"), String.valueOf(result.getMessage()));
        verify(notifier, never()).notifyCommentAuthor(any(), any(), eq(true), any());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    // ==================== 解除折叠 ====================

    @Test
    @DisplayName("解除折叠：置 is_hidden=0 + 通知评论者已恢复展示 + 审计动作为 UNFOLD")
    void unfoldSucceeds() {
        givenCommentExists();
        when(commentMapper.update(isNull(), any())).thenReturn(1);

        ResponseResult result = service.unfold(AdminContentType.COMMENT, COMMENT_ID, "AI 误伤，属正常表达");

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("hidden"));
        verify(notifier).notifyCommentAuthor("COMMENT", COMMENT_ID, false, "AI 误伤，属正常表达");
        ApAdminAuditLog audit = captureSuccessAudit();
        assertEquals(AdminContentFoldService.ACTION_UNFOLD, audit.getAction());
        assertTrue(audit.getDetail().contains("1 -> 0"), audit.getDetail());
    }

    @Test
    @DisplayName("解除折叠·本来就没折叠 → 报错，不做无意义写入")
    void unfoldWhenNotHiddenIsRejected() {
        givenCommentExists();
        when(commentMapper.update(isNull(), any())).thenReturn(0);

        ResponseResult result = service.unfold(AdminContentType.COMMENT, COMMENT_ID, "误伤");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("未被折叠"), String.valueOf(result.getMessage()));
        verify(notifier, never()).notifyCommentAuthor(any(), any(), eq(false), any());
    }

    // ==================== 失败留痕 ====================

    @Test
    @DisplayName("折叠：写入抛异常 → 记失败审计后原样抛出（不能把异常吞成'折叠成功'）")
    void foldRecordsFailureAndRethrows() {
        givenCommentExists();
        RuntimeException boom = new RuntimeException("DB 连接断了");
        when(commentMapper.update(isNull(), any())).thenThrow(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> service.fold(AdminContentType.COMMENT, COMMENT_ID, "含人身攻击"));

        assertEquals(boom, thrown);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).recordFailure(any(ApAdminAuditLog.class), msgCaptor.capture());
        assertEquals("DB 连接断了", msgCaptor.getValue());
        verify(auditRecorder, never()).recordSuccess(any());
        verify(notifier, never()).notifyCommentAuthor(any(), any(), eq(true), any());
    }

    // ==================== 工具 ====================

    private void givenCommentExists() {
        when(commentMapper.selectCount(any())).thenReturn(1L);
    }

    private ApComment normalComment() {
        ApComment c = new ApComment();
        c.setId(COMMENT_ID);
        c.setArticleId(ARTICLE_ID);
        c.setUserId(AUTHOR_ID);
        c.setUserName("路人甲");
        c.setContent("这句话说得不对");
        c.setIsHidden(1);
        c.setCreatedTime(new Date());
        return c;
    }

    private String captureCommentUpdateSql() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ApComment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(commentMapper).update(isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertNotNull(sql);
        return sql;
    }

    private ApAdminAuditLog captureSuccessAudit() {
        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(captor.capture());
        return captor.getValue();
    }
}
