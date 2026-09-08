package com.heima.content.controller.v1.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.mapper.comment.ApCommentLikeMapper;
import com.heima.content.mapper.comment.ApCommentMapper;
import com.heima.content.service.comment.impl.CommentAuditService;
import com.heima.model.comment.pojos.ApComment;
import com.heima.model.comment.pojos.ApCommentLike;
import com.heima.model.common.enums.AppHttpCodeEnum;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 文章评论接口 - 端到端集成测试
 *
 * 加载完整 Spring 上下文（SpringBootTest + AutoConfigureMockMvc），通过真实 HTTP 请求走
 * Controller → Service → Mapper → MySQL 全链路，并校验数据库真实落库结果。
 *
 * 隔离策略：
 * - 覆盖 app.internal-auth.secret 为空，使 ContentTokenInterceptor 按"本地直连"降级信任
 *   请求头 userId / nickName（不携带则视为匿名）。生产环境该密钥已配置，请求必须带 HMAC 签名头，
 *   测试环境置空可避免伪造签名头即可模拟登录态。
 * - @MockBean 屏蔽 CommentAuditService 异步审核，避免触发 AI 审核、行为上报、站内信等外部副作用，聚焦评论主链路
 * - 使用独立测试文章ID与用户ID，@AfterEach 清理 ap_comment / ap_comment_like 测试数据，不污染线上数据
 */
@SpringBootTest(properties = "app.internal-auth.secret=")
@AutoConfigureMockMvc
class ArticleCommentE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApCommentMapper apCommentMapper;

    @Autowired
    private ApCommentLikeMapper apCommentLikeMapper;

    /** 隔离异步审核及行为/通知副作用 */
    @MockBean
    private CommentAuditService commentAuditService;

    /**
     * 用 Mock 替换真实 RedissonClient，避免 CI/无 Redis 环境启动完整 Spring 上下文时
     * 因 Redisson 无法连接而失败。评论主链路不依赖 Redis 延迟队列/分布式锁。
     * RETURNS_DEEP_STUBS 让嵌套调用（getScript().scriptLoad()、getBlockingQueue() 等）返回可用的桩对象，
     * 避免 bean 初始化阶段（如 RateLimitAspect、延迟队列消费者）因 mock 默认返回 null 而 NPE。
     */
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private org.redisson.api.RedissonClient redissonClient;

    /** 替换延迟/关单消费组件，避免其 @PostConstruct 启动真实消费者线程（测试环境无 Redis，也无需这些任务消费者） */
    @MockBean
    private com.heima.content.schedule.listener.RedissonDelayQueue redissonDelayQueue;

    @MockBean
    private com.heima.content.service.order.impl.OrderTimeoutTask orderTimeoutTask;

    /** 独立测试文章ID（虚拟值，不与真实数据冲突） */
    private static final Long TEST_ARTICLE_ID = 9000000000000000001L;
    /** 独立测试用户ID */
    private static final String TEST_USER_ID = "7000001";
    private static final String TEST_NICKNAME = "端到端测试用户";

    @Test
    @DisplayName("端到端：发表评论→回复→点赞→查询列表→取消点赞 全链路")
    void testCommentFullFlow() throws Exception {
        doNothing().when(commentAuditService).asyncAuditComment(any());

        // 1. 发表一级评论
        Long topId = addComment("端到端一级评论");
        ApComment top = apCommentMapper.selectById(topId);
        assertThat(top).isNotNull();
        assertThat(top.getContent()).isEqualTo("端到端一级评论");
        assertThat(top.getParentId()).isNull();
        assertThat(top.getLikeCount()).isZero();
        assertThat(top.getReplyCount()).isZero();

        // 2. 回复一级评论
        Long replyId = replyComment(topId, topId, "端到端回复评论");
        ApComment reply = apCommentMapper.selectById(replyId);
        assertThat(reply).isNotNull();
        assertThat(reply.getParentId()).isEqualTo(topId);
        assertThat(reply.getRootId()).isEqualTo(topId);

        // 回复后父评论 replyCount 应为 1
        assertThat(apCommentMapper.selectById(topId).getReplyCount()).isEqualTo(1);

        // 3. 点赞一级评论
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/like", topId)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        // 点赞后 likeCount=1，且点赞表有记录
        assertThat(apCommentMapper.selectById(topId).getLikeCount()).isEqualTo(1);
        assertThat(countLikes(topId)).isEqualTo(1);

        // 4. 查询评论列表：应包含一级评论及其回复，且 isDigg=true
        MvcResult listResult = mockMvc.perform(get("/api/v1/comment/article/{id}/comments", TEST_ARTICLE_ID)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].commentId").value(topId))
                .andExpect(jsonPath("$.data.list[0].isDigg").value(true))
                .andExpect(jsonPath("$.data.list[0].replyCount").value(1))
                .andExpect(jsonPath("$.data.list[0].replyInfos[0].commentId").value(replyId))
                .andReturn();

        // 5. 取消点赞
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/like", topId)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));

        // 取消后 likeCount=0，点赞表无记录
        assertThat(apCommentMapper.selectById(topId).getLikeCount()).isZero();
        assertThat(countLikes(topId)).isZero();

        // 取消后再查列表 isDigg=false
        mockMvc.perform(get("/api/v1/comment/article/{id}/comments", TEST_ARTICLE_ID)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].isDigg").value(false));
    }

    @Test
    @DisplayName("端到端：未登录发表/回复/点赞评论均返回需要登录")
    void testMutationWithoutLogin() throws Exception {
        // 未携带登录头
        mockMvc.perform(post("/api/v1/comment/article/{id}/comment", TEST_ARTICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"未登录评论\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.NEED_LOGIN.getCode()));

        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/reply", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"未登录回复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.NEED_LOGIN.getCode()));

        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/like", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.NEED_LOGIN.getCode()));
    }

    @Test
    @DisplayName("端到端：发表空评论返回参数校验错误")
    void testAddCommentEmptyContent() throws Exception {
        mockMvc.perform(post("/api/v1/comment/article/{id}/comment", TEST_ARTICLE_ID)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("端到端：回复不存在的评论返回数据不存在")
    void testReplyToMissingComment() throws Exception {
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/reply", 999999999L)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"回复不存在的评论\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.DATA_NOT_EXIST.getCode()));
    }

    @Test
    @DisplayName("端到端：点赞不存在的评论返回数据不存在")
    void testLikeMissingComment() throws Exception {
        mockMvc.perform(post("/api/v1/comment/comment/{commentId}/like", 999999999L)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AppHttpCodeEnum.DATA_NOT_EXIST.getCode()));
    }

    // ==================== 辅助方法 ====================

    /** 发表一级评论，返回评论ID */
    private Long addComment(String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/comment/article/{id}/comment", TEST_ARTICLE_ID)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return parseCommentId(result);
    }

    /** 回复评论，返回回复评论ID */
    private Long replyComment(Long commentId, Long rootId, String content) throws Exception {
        Map<String, Object> body = Map.of("content", content, "rootId", rootId);
        MvcResult result = mockMvc.perform(post("/api/v1/comment/comment/{commentId}/reply", commentId)
                        .header("userId", TEST_USER_ID).header("nickName", TEST_NICKNAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return parseCommentId(result);
    }

    /** 从发表/回复响应中解析 data.commentId */
    private Long parseCommentId(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("data").get("commentId").asLong();
    }

    /** 统计某评论的点赞记录数 */
    private int countLikes(Long commentId) {
        return apCommentLikeMapper.selectCount(new LambdaQueryWrapper<ApCommentLike>()
                .eq(ApCommentLike::getCommentId, commentId)).intValue();
    }

    /** 清理测试文章下所有评论及对应点赞记录 */
    @AfterEach
    void cleanUp() {
        apCommentLikeMapper.delete(new LambdaQueryWrapper<ApCommentLike>()
                .inSql(ApCommentLike::getCommentId,
                        "SELECT id FROM ap_comment WHERE article_id = " + TEST_ARTICLE_ID));
        apCommentMapper.delete(new LambdaQueryWrapper<ApComment>()
                .eq(ApComment::getArticleId, TEST_ARTICLE_ID));
    }
}