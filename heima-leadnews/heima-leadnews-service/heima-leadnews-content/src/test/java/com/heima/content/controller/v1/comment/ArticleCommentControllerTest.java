package com.heima.content.controller.v1.comment;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.service.comment.ApCommentService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 文章评论接口 - 控制器层分层测试
 * 使用 standaloneSetup 构建 MockMvc，仅关注 HTTP 路由绑定、路径变量/请求体解析及登录态拦截，
 * 不加载 Spring 上下文，Service 层以 Mock 替身。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleCommentControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApCommentService apCommentService;

    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    private static final Long TEST_ARTICLE_ID = 2086414899941933058L;
    private static final Long TEST_COMMENT_ID = 1001L;
    private static final Long TEST_ROOT_ID = 1001L;
    private static final Integer TEST_USER_ID = 20001;

    @BeforeEach
    void setUp() {
        apCommentService = Mockito.mock(ApCommentService.class);
        ArticleCommentController controller = new ArticleCommentController();
        setService(controller);
        mockMvc = standaloneSetup(controller).build();

        ApUser user = new ApUser();
        user.setId(TEST_USER_ID);
        user.setNickname("测试用户");
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(user);
    }

    private void setService(ArticleCommentController controller) {
        try {
            java.lang.reflect.Field field = ArticleCommentController.class.getDeclaredField("apCommentService");
            field.setAccessible(true);
            field.set(controller, apCommentService);
        } catch (Exception e) {
            throw new IllegalStateException("注入 apCommentService 失败", e);
        }
    }

    @AfterEach
    void tearDown() {
        if (threadLocalMock != null) {
            threadLocalMock.close();
        }
    }

    // ==================== GET /article/{id}/comments ====================

    @Test
    @DisplayName("获取评论列表 - 默认参数转发到Service")
    void testGetArticleCommentsDefaultParams() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("list", java.util.Collections.emptyList());
        data.put("cursor", 0);
        data.put("has_more", false);
        when(apCommentService.getArticleComments(TEST_ARTICLE_ID, null, 10))
                .thenReturn(ResponseResult.okResult(data));

        mockMvc.perform(get("/api/v1/comment/article/{id}/comments", TEST_ARTICLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.has_more").value(false));

        verify(apCommentService).getArticleComments(eq(TEST_ARTICLE_ID), isNull(), eq(10));
    }

    @Test
    @DisplayName("获取评论列表 - 携带游标分页参数")
    void testGetArticleCommentsWithCursor() throws Exception {
        when(apCommentService.getArticleComments(TEST_ARTICLE_ID, 500L, 5))
                .thenReturn(ResponseResult.okResult(new HashMap<>()));

        mockMvc.perform(get("/api/v1/comment/article/{id}/comments", TEST_ARTICLE_ID)
                        .param("cursor", "500")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(apCommentService).getArticleComments(eq(TEST_ARTICLE_ID), eq(500L), eq(5));
    }

    // ==================== POST /article/{id}/comment ====================

    @Test
    @DisplayName("发表评论 - 未登录返回需要登录")
    void testAddArticleCommentNotLoggedIn() throws Exception {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        Map<String, String> body = new HashMap<>();
        body.put("content", "测试评论");
        mockMvc.perform(post("/api/v1/comment/article/{id}/comment", TEST_ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.NEED_LOGIN.getCode()));
    }

    @Test
    @DisplayName("发表评论 - 已登录时解析请求体并调用Service")
    void testAddArticleCommentLoggedIn() throws Exception {
        when(apCommentService.addArticleComment(eq(TEST_ARTICLE_ID), eq("测试评论")))
                .thenReturn(ResponseResult.okResult(new HashMap<>()));

        Map<String, String> body = new HashMap<>();
        body.put("content", "测试评论");
        mockMvc.perform(post("/api/v1/comment/article/{id}/comment", TEST_ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(apCommentService).addArticleComment(TEST_ARTICLE_ID, "测试评论");
    }

    @Test
    @DisplayName("发表评论 - 空请求体不会抛异常")
    void testAddArticleCommentEmptyBody() throws Exception {
        when(apCommentService.addArticleComment(eq(TEST_ARTICLE_ID), isNull()))
                .thenReturn(ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID));

        mockMvc.perform(post("/api/v1/comment/article/{id}/comment", TEST_ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(apCommentService).addArticleComment(TEST_ARTICLE_ID, null);
    }

    // ==================== POST /comment/{commentId}/reply ====================

    @Test
    @DisplayName("回复评论 - 未登录返回需要登录")
    void testReplyCommentNotLoggedIn() throws Exception {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        Map<String, Object> body = new HashMap<>();
        body.put("content", "回复内容");
        body.put("rootId", TEST_ROOT_ID);
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/reply", TEST_COMMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.NEED_LOGIN.getCode()));
    }

    @Test
    @DisplayName("回复评论 - 解析rootId并调用Service")
    void testReplyCommentLoggedIn() throws Exception {
        when(apCommentService.replyComment(TEST_COMMENT_ID, "回复内容", TEST_ROOT_ID))
                .thenReturn(ResponseResult.okResult(new HashMap<>()));

        Map<String, Object> body = new HashMap<>();
        body.put("content", "回复内容");
        body.put("rootId", TEST_ROOT_ID);
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/reply", TEST_COMMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(apCommentService).replyComment(TEST_COMMENT_ID, "回复内容", TEST_ROOT_ID);
    }

    @Test
    @DisplayName("回复评论 - rootId缺省时为null")
    void testReplyCommentWithoutRootId() throws Exception {
        when(apCommentService.replyComment(TEST_COMMENT_ID, "回复内容", null))
                .thenReturn(ResponseResult.okResult(new HashMap<>()));

        Map<String, Object> body = new HashMap<>();
        body.put("content", "回复内容");
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/reply", TEST_COMMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(apCommentService).replyComment(TEST_COMMENT_ID, "回复内容", null);
    }

    // ==================== POST /comment/{commentId}/like ====================

    @Test
    @DisplayName("点赞评论 - 未登录返回需要登录")
    void testDiggCommentNotLoggedIn() throws Exception {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/like", TEST_COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.NEED_LOGIN.getCode()));
    }

    @Test
    @DisplayName("点赞评论 - 已登录时调用Service")
    void testDiggCommentLoggedIn() throws Exception {
        when(apCommentService.diggComment(TEST_COMMENT_ID))
                .thenReturn(ResponseResult.okResult(Map.of("liked", true, "likeCount", 1)));

        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/like", TEST_COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true));

        verify(apCommentService).diggComment(TEST_COMMENT_ID);
    }
}