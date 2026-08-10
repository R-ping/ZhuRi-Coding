package com.heima.content.service.comment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.comment.ApCommentLikeMapper;
import com.heima.content.mapper.comment.ApCommentMapper;
import com.heima.model.comment.pojos.ApComment;
import com.heima.model.comment.pojos.ApCommentLike;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ApCommentServiceImplTest {

    @Mock
    private ApCommentMapper apCommentMapper;

    @Mock
    private ApCommentLikeMapper apCommentLikeMapper;

    @Mock
    private CommentAuditService commentAuditService;

    @Mock
    private ApArticleMapper apArticleMapper;

    @InjectMocks
    private ApCommentServiceImpl commentService;

    @Captor
    private ArgumentCaptor<ApComment> commentCaptor;

    @Captor
    private ArgumentCaptor<ApCommentLike> likeCaptor;

    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    private static final Long TEST_ARTICLE_ID = 2086414899941933058L;
    private static final Long TEST_COMMENT_ID = 1001L;
    private static final Long TEST_REPLY_COMMENT_ID = 1002L;
    private static final Long TEST_ROOT_ID = 1001L;
    private static final Integer TEST_USER_ID = 20001;
    private static final String TEST_CONTENT = "这是一条测试评论";

    private ApUser testUser;
    private ApComment testComment;
    private ApComment testReply;

    @BeforeEach
    void setUp() {
        testUser = new ApUser();
        testUser.setId(TEST_USER_ID);
        testUser.setNickname("测试用户");
        testUser.setImage("https://example.com/avatar.png");

        testComment = new ApComment();
        testComment.setId(TEST_COMMENT_ID);
        testComment.setArticleId(TEST_ARTICLE_ID);
        testComment.setUserId(TEST_USER_ID);
        testComment.setUserName("测试用户");
        testComment.setUserAvatar("https://example.com/avatar.png");
        testComment.setContent(TEST_CONTENT);
        testComment.setLikeCount(5);
        testComment.setReplyCount(2);
        testComment.setCreatedTime(new Date());

        testReply = new ApComment();
        testReply.setId(TEST_REPLY_COMMENT_ID);
        testReply.setArticleId(TEST_ARTICLE_ID);
        testReply.setUserId(TEST_USER_ID);
        testReply.setUserName("测试用户");
        testReply.setUserAvatar("https://example.com/avatar.png");
        testReply.setParentId(TEST_COMMENT_ID);
        testReply.setRootId(TEST_ROOT_ID);
        testReply.setContent("这是一条测试回复");
        testReply.setLikeCount(0);
        testReply.setReplyCount(0);
        testReply.setCreatedTime(new Date());

        // Mock AppThreadLocalUtil.getUser()
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(testUser);

        // 设置 ServiceImpl 的 baseMapper（MyBatis Plus 要求）
        ReflectionTestUtils.setField(commentService, "baseMapper", apCommentMapper);
    }

    @AfterEach
    void tearDown() {
        if (threadLocalMock != null) {
            threadLocalMock.close();
        }
    }

    // ==================== getArticleComments ====================

    @Test
    @DisplayName("获取评论列表 - 参数为空时返回错误")
    void testGetArticleCommentsNullArticleId() {
        ResponseResult result = commentService.getArticleComments(null, null, 10);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("获取评论列表 - 文章无评论时返回空列表")
    void testGetArticleCommentsEmpty() {
        when(apCommentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        ResponseResult result = commentService.getArticleComments(TEST_ARTICLE_ID, null, 10);
        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        List<?> list = (List<?>) data.get("list");
        assertTrue(list.isEmpty());
        assertFalse((Boolean) data.get("has_more"));
    }

    @Test
    @DisplayName("获取评论列表 - 有评论时返回列表")
    void testGetArticleCommentsWithData() {
        // 一级评论列表（多查一条判断 has_more，返回2条但size=1，所以有更多）
        List<ApComment> topComments = Arrays.asList(testComment, testReply);
        when(apCommentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(topComments)  // 第一次：一级评论
                .thenReturn(Collections.emptyList());  // 第二次：子回复

        // size=1 时，多查一条（LIMIT 2），返回2条，所以 has_more=true
        ResponseResult result = commentService.getArticleComments(TEST_ARTICLE_ID, null, 1);
        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        List<?> list = (List<?>) data.get("list");
        assertEquals(1, list.size());
        assertTrue((Boolean) data.get("has_more"));

        // 验证游标不为0
        Long cursor = (Long) data.get("cursor");
        assertTrue(cursor > 0);
    }

    // ==================== addArticleComment ====================

    @Test
    @DisplayName("发表评论 - 内容为空时返回错误")
    void testAddArticleCommentEmptyContent() {
        ResponseResult result = commentService.addArticleComment(TEST_ARTICLE_ID, "");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());

        result = commentService.addArticleComment(TEST_ARTICLE_ID, null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("发表评论 - 内容超过1000字时返回错误")
    void testAddArticleCommentTooLong() {
        String longContent = "a".repeat(1001);
        ResponseResult result = commentService.addArticleComment(TEST_ARTICLE_ID, longContent);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("发表评论 - 未登录时返回需要登录")
    void testAddArticleCommentNotLoggedIn() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = commentService.addArticleComment(TEST_ARTICLE_ID, TEST_CONTENT);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
    }

    @Test
    @DisplayName("发表评论 - 成功发表评论")
    void testAddArticleCommentSuccess() {
        when(apCommentMapper.insert(any(ApComment.class))).thenAnswer(invocation -> {
            ApComment comment = invocation.getArgument(0);
            comment.setId(999L); // 模拟插入后设置ID
            return 1;
        });
        doNothing().when(apArticleMapper).updateCommentCount(anyLong(), eq(1));
        doNothing().when(commentAuditService).asyncAuditComment(any());

        ResponseResult result = commentService.addArticleComment(TEST_ARTICLE_ID, TEST_CONTENT);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertEquals(TEST_CONTENT, data.get("content"));
        assertEquals(0, data.get("diggCount"));
        assertFalse((Boolean) data.get("isDigg"));

        // 验证插入评论
        verify(apCommentMapper).insert(commentCaptor.capture());
        ApComment saved = commentCaptor.getValue();
        assertEquals(TEST_ARTICLE_ID, saved.getArticleId());
        assertEquals(TEST_USER_ID, saved.getUserId());
        assertEquals(TEST_CONTENT.trim(), saved.getContent());
        assertEquals("测试用户", saved.getUserName());

        // 验证更新文章评论数
        verify(apArticleMapper).updateCommentCount(TEST_ARTICLE_ID, 1);
    }

    // ==================== replyComment ====================

    @Test
    @DisplayName("回复评论 - 内容为空时返回错误")
    void testReplyCommentEmptyContent() {
        ResponseResult result = commentService.replyComment(TEST_COMMENT_ID, "", TEST_ROOT_ID);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("回复评论 - 父评论不存在时返回错误")
    void testReplyCommentParentNotFound() {
        when(apCommentMapper.selectById(TEST_COMMENT_ID)).thenReturn(null);

        ResponseResult result = commentService.replyComment(TEST_COMMENT_ID, TEST_CONTENT, TEST_ROOT_ID);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("回复评论 - 成功回复")
    void testReplyCommentSuccess() {
        when(apCommentMapper.selectById(TEST_COMMENT_ID)).thenReturn(testComment);
        when(apCommentMapper.insert(any(ApComment.class))).thenAnswer(invocation -> {
            ApComment reply = invocation.getArgument(0);
            reply.setId(999L);
            return 1;
        });
        when(apCommentMapper.updateById(any(ApComment.class))).thenReturn(1);

        ResponseResult result = commentService.replyComment(TEST_COMMENT_ID, TEST_CONTENT, TEST_ROOT_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertEquals(TEST_CONTENT, data.get("content"));
        assertEquals(0, data.get("diggCount"));

        // 验证插入回复
        verify(apCommentMapper).insert(commentCaptor.capture());
        ApComment saved = commentCaptor.getValue();
        assertEquals(TEST_COMMENT_ID, saved.getParentId());
        assertEquals(TEST_ROOT_ID, saved.getRootId());
        assertEquals(TEST_CONTENT.trim(), saved.getContent());

        // 验证父评论回复数+1
        assertEquals(3, testComment.getReplyCount().intValue());
        verify(apCommentMapper).updateById(testComment);
    }

    // ==================== diggComment ====================

    @Test
    @DisplayName("点赞评论 - 评论不存在时返回错误")
    void testDiggCommentNotFound() {
        when(apCommentMapper.selectById(TEST_COMMENT_ID)).thenReturn(null);

        ResponseResult result = commentService.diggComment(TEST_COMMENT_ID);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("点赞评论 - 成功点赞（未点赞过）")
    void testDiggCommentLike() {
        when(apCommentMapper.selectById(TEST_COMMENT_ID)).thenReturn(testComment);
        when(apCommentLikeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(apCommentLikeMapper.insert(any(ApCommentLike.class))).thenReturn(1);
        when(apCommentMapper.updateById(any(ApComment.class))).thenReturn(1);

        ResponseResult result = commentService.diggComment(TEST_COMMENT_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertTrue((Boolean) data.get("liked"));
        assertEquals(6, data.get("likeCount"));

        verify(apCommentLikeMapper).insert(likeCaptor.capture());
        assertEquals(TEST_COMMENT_ID, likeCaptor.getValue().getCommentId());
        assertEquals(TEST_USER_ID, likeCaptor.getValue().getUserId());
    }

    @Test
    @DisplayName("点赞评论 - 取消点赞（已点赞过）")
    void testDiggCommentUnlike() {
        ApCommentLike existingLike = new ApCommentLike();
        existingLike.setId(1L);
        existingLike.setCommentId(TEST_COMMENT_ID);
        existingLike.setUserId(TEST_USER_ID);

        when(apCommentMapper.selectById(TEST_COMMENT_ID)).thenReturn(testComment);
        when(apCommentLikeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLike);
        when(apCommentLikeMapper.deleteById(1L)).thenReturn(1);
        when(apCommentMapper.updateById(any(ApComment.class))).thenReturn(1);

        ResponseResult result = commentService.diggComment(TEST_COMMENT_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertFalse((Boolean) data.get("liked"));
        assertEquals(4, data.get("likeCount")); // 5 - 1 = 4

        verify(apCommentLikeMapper).deleteById(1L);
    }
}