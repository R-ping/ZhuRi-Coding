package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.coding.dtos.CodingQuestionSubmitDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingSupplyServiceImpl 单元测试（Coding 延展第一层 · 题库供给）
 *
 * 覆盖：投稿结构校验、题干查重（含驳回记录）、AI 质检通过/驳回/不可用降级、
 * 来源文章归属校验；文章生成（作者校验、每日次数上限、解析采纳与重复跳过、模型不可用）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("题库供给服务单元测试")
class CodingSupplyServiceImplTest {

    private static final Integer USER_ID = 1001;

    @Mock
    private ApCodingQuestionMapper questionMapper;
    @Mock
    private ApArticleMapper articleMapper;
    @Mock
    private ApArticleContentMapper contentMapper;
    @Mock
    private AiLlmGateway llmGateway;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private CodingSupplyServiceImpl service;

    // ---------- 辅助 ----------

    private ApArticle article(Long id, Long authorId, byte status) {
        ApArticle article = new ApArticle();
        article.setId(id);
        article.setTitle("Redis 缓存实战");
        article.setAuthorId(authorId);
        article.setStatus(status);
        return article;
    }

    private CodingQuestionSubmitDTO validDto() {
        CodingQuestionSubmitDTO dto = new CodingQuestionSubmitDTO();
        dto.setStem("以下关于 Redis 缓存穿透的描述，哪一项是正确的？");
        dto.setQuestionType(ApCodingQuestion.TYPE_SINGLE);
        dto.setOptions(List.of("查询不存在的数据", "查询热点数据", "缓存过期", "缓存雪崩"));
        dto.setAnswer(List.of(0));
        dto.setExplanation("缓存穿透指查询不存在的数据。");
        dto.setDifficulty(ApCodingQuestion.DIFFICULTY_EASY);
        dto.setTags("Redis,缓存");
        return dto;
    }

    private ApCodingQuestion existing(int status) {
        ApCodingQuestion q = new ApCodingQuestion();
        q.setId(1L);
        q.setStem("已有题目");
        q.setStatus(status);
        return q;
    }

    // ==================== 作者投稿 ====================

    @Test
    @DisplayName("submitQuestion - 题干过短：结构校验拒绝且不调用 AI")
    void testSubmitInvalidStem() {
        CodingQuestionSubmitDTO dto = validDto();
        dto.setStem("太短");

        ResponseResult result = service.submitQuestion(USER_ID, dto);

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("题干长度"));
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("submitQuestion - 单选答案多于 1 个：拒绝")
    void testSubmitSingleChoiceMultipleAnswers() {
        CodingQuestionSubmitDTO dto = validDto();
        dto.setAnswer(List.of(0, 1));

        ResponseResult result = service.submitQuestion(USER_ID, dto);

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("单选题"));
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("submitQuestion - 题干重复：拒绝且不调用 AI")
    void testSubmitDuplicate() {
        when(questionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(ApCodingQuestion.STATUS_PUBLISHED));

        ResponseResult result = service.submitQuestion(USER_ID, validDto());

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("已有相同题干"));
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("submitQuestion - 题干此前被驳回：提示修改后再提交")
    void testSubmitDuplicateRejectedBefore() {
        when(questionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(ApCodingQuestion.STATUS_REJECTED));

        ResponseResult result = service.submitQuestion(USER_ID, validDto());

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("未通过质检"));
    }

    @Test
    @DisplayName("submitQuestion - AI 质检不通过：落库为已驳回并返回原因")
    void testSubmitAuditReject() {
        when(questionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(llmGateway.generateOrNull(eq(AiFeatures.QUESTION_AUDIT), anyString(), anyString(), any(), any()))
            .thenReturn("{\"pass\":false,\"reason\":\"答案不唯一\"}");

        ResponseResult result = service.submitQuestion(USER_ID, validDto());

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("答案不唯一"));
        ArgumentCaptor<ApCodingQuestion> captor = ArgumentCaptor.forClass(ApCodingQuestion.class);
        verify(questionMapper).insert(captor.capture());
        assertEquals(ApCodingQuestion.STATUS_REJECTED, captor.getValue().getStatus().intValue());
        assertEquals("答案不唯一", captor.getValue().getRejectReason());
        assertEquals(ApCodingQuestion.SOURCE_AUTHOR, captor.getValue().getSourceType().intValue());
    }

    @Test
    @DisplayName("submitQuestion - AI 质检通过：直接上架并标注出题人")
    void testSubmitAuditPass() {
        when(questionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(llmGateway.generateOrNull(eq(AiFeatures.QUESTION_AUDIT), anyString(), anyString(), any(), any()))
            .thenReturn("{\"pass\":true,\"reason\":\"\"}");

        ResponseResult result = service.submitQuestion(USER_ID, validDto());

        assertEquals(200, result.getCode().intValue());
        ArgumentCaptor<ApCodingQuestion> captor = ArgumentCaptor.forClass(ApCodingQuestion.class);
        verify(questionMapper).insert(captor.capture());
        ApCodingQuestion saved = captor.getValue();
        assertEquals(ApCodingQuestion.STATUS_PUBLISHED, saved.getStatus().intValue());
        assertEquals(ApCodingQuestion.SOURCE_AUTHOR, saved.getSourceType().intValue());
        assertEquals(USER_ID.longValue(), saved.getSourceUserId().longValue());
        assertEquals("[0]", saved.getAnswer());
        assertTrue(saved.getStemHash().length() == 32);
    }

    @Test
    @DisplayName("submitQuestion - AI 质检不可用：fail-open 按格式校验放行")
    void testSubmitAuditUnavailableFailOpen() {
        when(questionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(llmGateway.generateOrNull(eq(AiFeatures.QUESTION_AUDIT), anyString(), anyString(), any(), any()))
            .thenReturn(null);

        ResponseResult result = service.submitQuestion(USER_ID, validDto());

        assertEquals(200, result.getCode().intValue());
        verify(questionMapper).insert(any(ApCodingQuestion.class));
    }

    @Test
    @DisplayName("submitQuestion - 来源文章不是自己的：拒绝")
    void testSubmitArticleNotOwned() {
        CodingQuestionSubmitDTO dto = validDto();
        dto.setArticleId(5L);
        when(articleMapper.selectById(5L)).thenReturn(article(5L, 999L, (byte) 9));

        ResponseResult result = service.submitQuestion(USER_ID, dto);

        assertEquals(403, result.getCode().intValue());
        assertTrue(result.getMessage().contains("自己的已发布文章"));
        verifyNoInteractions(llmGateway);
    }

    // ==================== 文章 AI 生成 ====================

    @Test
    @DisplayName("generateFromArticle - 文章不存在/未发布：拒绝")
    void testGenerateArticleNotPublished() {
        when(articleMapper.selectById(5L)).thenReturn(article(5L, USER_ID.longValue(), (byte) 1));

        ResponseResult result = service.generateFromArticle(USER_ID, 5L);

        assertEquals(400, result.getCode().intValue());
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("generateFromArticle - 非文章作者：拒绝")
    void testGenerateNotAuthor() {
        when(articleMapper.selectById(5L)).thenReturn(article(5L, 999L, (byte) 9));

        ResponseResult result = service.generateFromArticle(USER_ID, 5L);

        assertEquals(403, result.getCode().intValue());
        assertTrue(result.getMessage().contains("只能为自己的文章出题"));
    }

    @Test
    @DisplayName("generateFromArticle - 超出每日次数上限：拒绝")
    void testGenerateDailyLimit() {
        when(articleMapper.selectById(5L)).thenReturn(article(5L, USER_ID.longValue(), (byte) 9));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(6L);

        ResponseResult result = service.generateFromArticle(USER_ID, 5L);

        assertEquals(429, result.getCode().intValue());
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("generateFromArticle - 解析采纳有效题目并按题干 MD5 跳过重复")
    void testGenerateParsesAndSkipsDuplicate() {
        when(articleMapper.selectById(5L)).thenReturn(article(5L, USER_ID.longValue(), (byte) 9));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        ApArticleContent content = new ApArticleContent();
        content.setArticleId(5L);
        content.setContent("Redis 缓存穿透是指查询一个一定不存在的数据，缓存层与存储层都不会命中……");
        when(contentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(content);
        when(llmGateway.generateOrNull(eq(AiFeatures.QUESTION_GENERATE), anyString(), anyString(), any(), any()))
            .thenReturn("["
                + "{\"stem\":\"Redis 缓存穿透时，布隆过滤器的正确作用是哪一项？\","
                + "\"questionType\":1,\"options\":[\"拦截不存在的数据\",\"提升写入速度\",\"压缩缓存\",\"持久化数据\"],"
                + "\"answer\":[0],\"explanation\":\"布隆过滤器可拦截不存在的查询。\",\"difficulty\":2,\"tags\":[\"Redis\"]},"
                + "{\"stem\":\"关于缓存雪崩的成因，以下哪一项描述是正确的？\","
                + "\"questionType\":1,\"options\":[\"大量缓存同时过期\",\"单条数据不存在\",\"网络抖动\",\"磁盘写满\"],"
                + "\"answer\":[0],\"explanation\":\"同一时刻大量缓存失效会压垮存储层。\",\"difficulty\":2,\"tags\":[\"缓存\"]}"
                + "]");
        when(questionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L, 1L);

        ResponseResult result = service.generateFromArticle(USER_ID, 5L);

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2, data.get("generated"));
        assertEquals(1, data.get("inserted"));
        assertEquals(1, data.get("skipped"));
        ArgumentCaptor<ApCodingQuestion> captor = ArgumentCaptor.forClass(ApCodingQuestion.class);
        verify(questionMapper).insert(captor.capture());
        ApCodingQuestion saved = captor.getValue();
        assertEquals(ApCodingQuestion.SOURCE_AI, saved.getSourceType().intValue());
        assertEquals(5L, saved.getSourceArticleId().longValue());
        assertEquals(ApCodingQuestion.STATUS_PUBLISHED, saved.getStatus().intValue());
        assertEquals(ApCodingQuestion.DIFFICULTY_MEDIUM, saved.getDifficulty().intValue());
    }

    @Test
    @DisplayName("generateFromArticle - 模型不可用：返回生成失败")
    void testGenerateModelUnavailable() {
        when(articleMapper.selectById(5L)).thenReturn(article(5L, USER_ID.longValue(), (byte) 9));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        ApArticleContent content = new ApArticleContent();
        content.setArticleId(5L);
        content.setContent("正文内容足够长，用于触发模型调用前的正文校验……");
        when(contentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(content);
        when(llmGateway.generateOrNull(eq(AiFeatures.QUESTION_GENERATE), anyString(), anyString(), any(), any()))
            .thenReturn(null);

        ResponseResult result = service.generateFromArticle(USER_ID, 5L);

        assertEquals(500, result.getCode().intValue());
        verify(questionMapper, never()).insert(any(ApCodingQuestion.class));
    }
}