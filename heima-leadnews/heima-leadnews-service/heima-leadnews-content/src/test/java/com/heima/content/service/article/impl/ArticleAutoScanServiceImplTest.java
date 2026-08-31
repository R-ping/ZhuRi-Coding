package com.heima.content.service.article.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.content.service.article.processor.AIViolationProcessor;
import com.heima.content.service.article.processor.AuditFailProcessor;
import com.heima.content.service.article.processor.AuditProcessorContext;
import com.heima.content.service.article.processor.BehaviorEventProcessor;
import com.heima.content.service.article.processor.ImageScanProcessor;
import com.heima.content.service.article.processor.PowerBonusProcessor;
import com.heima.content.service.article.processor.SimilarityProcessor;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ArticleAutoScanServiceImpl 单元测试
 *
 * 覆盖责任链编排的核心逻辑：
 * - 正常审核通过流程（所有Processor依次执行）
 * - AI违规检测失败
 * - 图片审核失败
 * - 非审核中状态跳过
 * - 文章不存在
 */
@ExtendWith(MockitoExtension.class)
class ArticleAutoScanServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApArticleContentMapper apArticleContentMapper;
    @Mock
    private AIViolationProcessor aiViolationProcessor;
    @Mock
    private ImageScanProcessor imageScanProcessor;
    @Mock
    private SimilarityProcessor similarityProcessor;
    @Mock
    private PowerBonusProcessor powerBonusProcessor;
    @Mock
    private BehaviorEventProcessor behaviorEventProcessor;
    @Mock
    private AuditFailProcessor auditFailProcessor;
    @Mock
    private ArticleTaskService articleTaskService;

    @InjectMocks
    private ArticleAutoScanServiceImpl autoScanService;

    @Captor
    private ArgumentCaptor<ApArticle> articleCaptor;
    @Captor
    private ArgumentCaptor<String> failReasonCaptor;

    private ApArticle normalArticle;
    private ApArticleContent articleContent;
    private static final Long TEST_ARTICLE_ID = 2086414899941933058L;
    private static final Long TEST_AUTHOR_ID = 20001L;
    private static final String TEST_CONTENT = "# 测试文章内容\n这是一篇用于单元测试的文章。";

    @BeforeEach
    void setUp() {
        normalArticle = new ApArticle();
        normalArticle.setId(TEST_ARTICLE_ID);
        normalArticle.setAuthorId(TEST_AUTHOR_ID);
        normalArticle.setTitle("测试文章标题");
        normalArticle.setAuthorName("测试作者");
        normalArticle.setAuthorImage("https://example.com/avatar.png");
        normalArticle.setStatus(Status.SUBMIT.getCode());
        normalArticle.setPublishTime(new Date());
        normalArticle.setCoverImage("");

        articleContent = new ApArticleContent();
        articleContent.setId(1L);
        articleContent.setArticleId(TEST_ARTICLE_ID);
        articleContent.setContent(TEST_CONTENT);
    }

    // ==================== 前置检查 ====================

    @Test
    @DisplayName("文章不存在时返回false")
    void testArticleNotFound() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertFalse(future.get());
    }

    @Test
    @DisplayName("文章状态非审核中时跳过审核")
    void testArticleNotInSubmitStatus() throws Exception {
        normalArticle.setStatus(Status.PUBLISHED.getCode());
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        // 所有Processor不应被调用
        verify(aiViolationProcessor, never()).process(any(), anyString(), any());
        verify(imageScanProcessor, never()).process(any(), anyString(), any());
    }

    // ==================== AI违规检测 ====================

    @Test
    @DisplayName("AI违规检测失败时调用AuditFailProcessor并返回false")
    void testAiViolationFail() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        doAnswer(invocation -> {
            AuditProcessorContext ctx = invocation.getArgument(2);
            ctx.putExtra("failReason", "政治敏感: 文章包含违规内容");
            return false;
        }).when(aiViolationProcessor).process(any(), anyString(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertFalse(future.get());

        // 验证AuditFailProcessor被调用
        verify(auditFailProcessor).handleFail(articleCaptor.capture(), anyString());
        assertEquals(TEST_ARTICLE_ID, articleCaptor.getValue().getId());

        // 后续Processor不应被调用
        verify(imageScanProcessor, never()).process(any(), anyString(), any());
        verify(similarityProcessor, never()).process(any(), anyString(), any());
        verify(powerBonusProcessor, never()).process(any(), anyString(), any());
        verify(behaviorEventProcessor, never()).process(any(), anyString(), any());
        verify(articleTaskService, never()).addArticleToTask(anyLong(), any());
    }

    @Test
    @DisplayName("AI违规检测通过后继续执行后续流程")
    void testAiViolationPass() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        // 验证所有Processor都被执行
        verify(similarityProcessor).process(any(), anyString(), any());
        verify(powerBonusProcessor).process(any(), anyString(), any());
        verify(behaviorEventProcessor).process(any(), anyString(), any());
        verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());
    }

    // ==================== 图片审核 ====================

    @Test
    @DisplayName("图片审核失败时调用AuditFailProcessor并返回false")
    void testImageScanFail() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        doAnswer(invocation -> {
            AuditProcessorContext ctx = invocation.getArgument(2);
            ctx.putExtra("failReason", "图片违规: 包含敏感图片");
            return false;
        }).when(imageScanProcessor).process(any(), anyString(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertFalse(future.get());

        // 验证AuditFailProcessor被调用
        verify(auditFailProcessor).handleFail(articleCaptor.capture(), failReasonCaptor.capture());
        assertEquals(TEST_ARTICLE_ID, articleCaptor.getValue().getId());
        assertEquals("图片违规: 包含敏感图片", failReasonCaptor.getValue());

        // 后续Processor不应被调用
        verify(similarityProcessor, never()).process(any(), anyString(), any());
        verify(powerBonusProcessor, never()).process(any(), anyString(), any());
        verify(behaviorEventProcessor, never()).process(any(), anyString(), any());
    }

    // ==================== 完整流程 ====================

    @Test
    @DisplayName("完整审核通过流程：所有Processor依次执行")
    void testFullPassFlow() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        // 验证关键交互
        verify(apArticleMapper).selectById(TEST_ARTICLE_ID);
        verify(aiViolationProcessor).process(any(), anyString(), any());
        verify(imageScanProcessor).process(any(), anyString(), any());
        verify(similarityProcessor).process(any(), anyString(), any());
        verify(powerBonusProcessor).process(any(), anyString(), any());
        verify(behaviorEventProcessor).process(any(), anyString(), any());
        verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());

        // 审核失败不应被调用
        verify(auditFailProcessor, never()).handleFail(any(), anyString());
    }

    @Test
    @DisplayName("内容为空时仍能正常处理")
    void testEmptyContent() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        ApArticleContent emptyContent = new ApArticleContent();
        emptyContent.setArticleId(TEST_ARTICLE_ID);
        emptyContent.setContent("");
        when(apArticleContentMapper.selectOne(any())).thenReturn(emptyContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        verify(similarityProcessor).process(any(), anyString(), any());
        verify(powerBonusProcessor).process(any(), anyString(), any());
        verify(behaviorEventProcessor).process(any(), anyString(), any());
    }

    // ==================== Processor测试 ====================

    @Test
    @DisplayName("所有Processor返回true时审核通过")
    void testAllProcessorsPass() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        // 验证所有Processor都被调用
        verify(aiViolationProcessor).process(any(), anyString(), any());
        verify(imageScanProcessor).process(any(), anyString(), any());
        verify(similarityProcessor).process(any(), anyString(), any());
        verify(powerBonusProcessor).process(any(), anyString(), any());
        verify(behaviorEventProcessor).process(any(), anyString(), any());
    }
}