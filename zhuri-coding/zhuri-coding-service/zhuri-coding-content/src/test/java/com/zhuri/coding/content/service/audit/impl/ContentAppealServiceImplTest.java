package com.zhuri.coding.content.service.audit.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.audit.ApContentAppealMapper;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.model.audit.pojos.ApContentAppeal;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 申诉提交的并发去重。
 *
 * <p>应用层是「先 selectCount 再 insert」两段判断，挡不住并发；真正的闸是唯一索引
 * {@code uk_active_uniq}（见迁移脚本）。这里钉住的是：撞唯一键时不能把异常抛给前端，
 * 而要跟"已有处理中申诉"同一句提示。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("内容申诉提交")
class ContentAppealServiceImplTest {

    @Mock private ApContentAppealMapper appealMapper;
    @Mock private ApCommentMapper apCommentMapper;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper contentMapper;
    @Mock private Executor aiSseExecutor;

    @InjectMocks private ContentAppealServiceImpl appealService;

    private static final Long COMMENT_ID = 66L;
    private static final Integer APPLICANT = 7;

    private void ownComment() {
        ApComment comment = new ApComment();
        comment.setId(COMMENT_ID);
        comment.setUserId(APPLICANT);
        when(apCommentMapper.selectById(COMMENT_ID)).thenReturn(comment);
    }

    @Test
    @DisplayName("并发撞唯一索引：转成『已有处理中的申诉』，不把数据库异常抛给前端")
    void duplicateKeyBecomesFriendlyError() {
        ownComment();
        when(appealMapper.selectCount(any())).thenReturn(0L);
        when(appealMapper.insert(any(ApContentAppeal.class)))
                .thenThrow(new DuplicateKeyException("uk_active_uniq"));

        ResponseResult result = appealService.submit(
                ApContentAppeal.TYPE_COMMENT_HIDDEN, COMMENT_ID, "误判", APPLICANT);

        assertEquals(500, result.getCode());
        assertTrue(String.valueOf(result.getMessage()).contains("处理中"));
    }

    @Test
    @DisplayName("预查已有处理中申诉：直接拒绝，不再尝试插入")
    void pendingExistsRejectedWithoutInsert() {
        ownComment();
        when(appealMapper.selectCount(any())).thenReturn(1L);

        ResponseResult result = appealService.submit(
                ApContentAppeal.TYPE_COMMENT_HIDDEN, COMMENT_ID, "误判", APPLICANT);

        assertEquals(500, result.getCode());
        verify(appealMapper, never()).insert(any(ApContentAppeal.class));
    }
}
