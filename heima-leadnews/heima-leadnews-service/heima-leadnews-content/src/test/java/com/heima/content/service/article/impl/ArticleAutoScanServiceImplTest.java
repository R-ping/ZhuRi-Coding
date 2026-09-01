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
import com.heima.content.service.article.AuditRecordService;
import com.heima.content.service.article.processor.AIViolationProcessor;
import com.heima.content.service.article.processor.AuditFailProcessor;
import com.heima.content.service.article.processor.AuditProcessorContext;
import com.heima.content.service.article.processor.AuditRetryableException;
import com.heima.content.service.article.processor.BehaviorEventProcessor;
import com.heima.content.service.article.processor.ImageScanProcessor;
import com.heima.content.service.article.processor.PowerBonusProcessor;
import com.heima.content.service.article.processor.SimilarityProcessor;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ArticleAutoScanServiceImpl 单元测试
 *
 * 覆盖责任链编排（@Order 由 Spring 排序注入，本测试以传入 List 顺序执行）：
 * - 审核通过流程（所有环节依次执行）
 * - 业务判定环节返回 false → 正常驳回
 * - 可重试环节失败 → 有界重试（重试成功 / 耗尽转终态失败）
 * - 前置检查（文章不存在 / 非审核中跳过）
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
    @Mock
    private AuditRecordService auditRecordService;

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

        // 手动构造（责任链 List 传入；实际排序由 Spring 按 @Order 注入）
        autoScanService = new ArticleAutoScanServiceImpl(apArticleMapper, apArticleContentMapper,
            List.of(aiViolationProcessor, imageScanProcessor, similarityProcessor,
                powerBonusProcessor, behaviorEventProcessor),
            auditFailProcessor, articleTaskService, auditRecordService);
        // 测试中把重试配置压到小值，避免真实等待（@Value 字段在单测中未注入，需显式赋值）
        setField("auxRetryBackoffMs", 1L);
        setField("auxRetryAttempts", 3L);
    }

    private void setField(String name, long value) {
        try {
            java.lang.reflect.Field f = ArticleAutoScanServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            if (f.getType() == int.class) {
                f.setInt(autoScanService, (int) value);
            } else {
                f.setLong(autoScanService, value);
            }
        } catch (Exception e) {
            throw new IllegalStateException("反射设置字段失败: " + name, e);
        }
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

    // ==================== 业务判定环节（返回 false 即驳回） ====================

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

        verify(auditFailProcessor).handleFail(articleCaptor.capture(), anyString());
        assertEquals(TEST_ARTICLE_ID, articleCaptor.getValue().getId());

        // 后续环节不应被调用
        verify(imageScanProcessor, never()).process(any(), anyString(), any());
        verify(similarityProcessor, never()).process(any(), anyString(), any());
        verify(powerBonusProcessor, never()).process(any(), anyString(), any());
        verify(behaviorEventProcessor, never()).process(any(), anyString(), any());
        verify(articleTaskService, never()).addArticleToTask(anyLong(), any());
    }

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

        verify(auditFailProcessor).handleFail(articleCaptor.capture(), failReasonCaptor.capture());
        assertEquals("图片违规: 包含敏感图片", failReasonCaptor.getValue());
        verify(similarityProcessor, never()).process(any(), anyString(), any());
    }

    // ==================== 可重试环节（isRetryable=true） ====================

    @Test
    @DisplayName("可重试环节失败后重试成功 → 审核通过")
    void testRetryableStageRetriesThenPass() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.isRetryable()).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new AuditRetryableException("相似度服务暂时不可用");
            }
            return true;
        }).when(similarityProcessor).process(any(), anyString(), any());
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        // 失败 1 次 + 重试成功 1 次
        assertEquals(2, calls.get());
        verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());
        verify(auditFailProcessor, never()).handleFail(any(), anyString());
    }

    @Test
    @DisplayName("可重试环节重试耗尽 → 顶层兜底转终态失败")
    void testRetryableStageExhausted() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.isRetryable()).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any()))
            .thenThrow(new AuditRetryableException("相似度服务持续不可用"));
        setField("auxRetryAttempts", 2);

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertFalse(future.get());

        // 顶层兜底：转终态失败并通知作者
        verify(auditFailProcessor).handleFail(any(), eq(AuditFailProcessor.SYSTEM_ERROR_REASON));
        verify(articleTaskService, never()).addArticleToTask(anyLong(), any());
    }

    // ==================== 完整流程 ====================

    @Test
    @DisplayName("完整审核通过流程：所有环节依次执行")
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

        verify(aiViolationProcessor).process(any(), anyString(), any());
        verify(imageScanProcessor).process(any(), anyString(), any());
        verify(similarityProcessor).process(any(), anyString(), any());
        verify(powerBonusProcessor).process(any(), anyString(), any());
        verify(behaviorEventProcessor).process(any(), anyString(), any());
        verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());
        verify(auditFailProcessor, never()).handleFail(any(), anyString());
        // 审核通过：写入 PASS 审计轨迹
        verify(auditRecordService).record(any(), anyString(), eq(com.heima.common.constants.ArticleConstants.AUDIT_STATUS_PASS), anyString());
    }
}
