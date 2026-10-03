package com.zhuri.coding.content.service.article.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.content.mapper.article.ApArticleConfigMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleDraftMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleRevisionService;
import com.zhuri.coding.content.service.article.ArticleTaskService;
import com.zhuri.coding.content.service.article.AuditRecordService;
import com.zhuri.coding.content.service.article.processor.AIViolationProcessor;
import com.zhuri.coding.content.service.article.processor.ArticleAuditProcessor;
import com.zhuri.coding.content.service.article.processor.AuditFailProcessor;
import com.zhuri.coding.content.service.article.processor.AuditProcessorContext;
import com.zhuri.coding.content.service.article.processor.AuditRetryableException;
import com.zhuri.coding.content.service.article.processor.BehaviorEventProcessor;
import com.zhuri.coding.content.service.article.processor.ImageScanProcessor;
import com.zhuri.coding.content.service.article.processor.PowerBonusProcessor;
import com.zhuri.coding.content.service.article.processor.QualityNotificationProcessor;
import com.zhuri.coding.content.service.article.processor.SimilarityProcessor;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
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
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock
    private ApArticleDraftMapper apArticleDraftMapper;
    @Mock
    private ArticleRevisionService articleRevisionService;

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

    // ==================== 可重试环节（抛 AuditRetryableException） ====================

    @Test
    @DisplayName("环节抛AuditRetryableException失败后重试成功 → 审核通过")
    void testRetryableStageRetriesThenPass() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
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
    @DisplayName("环节抛AuditRetryableException重试耗尽 → 顶层兜底转终态失败")
    void testRetryableStageExhausted() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any()))
            .thenThrow(new AuditRetryableException("相似度服务持续不可用"));
        setField("auxRetryAttempts", 2);

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertFalse(future.get());

        // 顶层兜底：转终态失败并通知作者。
        // 注意断言的是 handleSystemErrorFail 而非 handleFail —— 系统异常必须走"非违规"文案，
        // 否则 AI 抖动会被作者感知为"内容违规被删"（本断言即为此护栏）。
        verify(auditFailProcessor).handleSystemErrorFail(any(), eq(AuditFailProcessor.SYSTEM_ERROR_REASON));
        verify(articleTaskService, never()).addArticleToTask(anyLong(), any());
    }

    @Test
    @DisplayName("AI服务不可用抛AuditRetryableException → 重试成功后通过(不再直接判违规)")
    void testAiViolationServiceUnavailableRetriesThenPass() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        AtomicInteger calls = new AtomicInteger();
        // 第一次：AI 服务瞬时故障 → 抛可重试异常；第二次恢复正常
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new AuditRetryableException("LLM 连接超时");
            }
            return true;
        }).when(aiViolationProcessor).process(any(), anyString(), any());
        when(imageScanProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(similarityProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        assertEquals(2, calls.get());
        verify(auditFailProcessor, never()).handleFail(any(), anyString());
        verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());
    }

    @Test
    @DisplayName("图片审核服务异常抛AuditRetryableException → 重试成功后通过")
    void testImageScanServiceUnavailableRetriesThenPass() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        when(aiViolationProcessor.process(any(), anyString(), any())).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new AuditRetryableException("图片审核服务超时");
            }
            return true;
        }).when(imageScanProcessor).process(any(), anyString(), any());
        when(similarityProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(powerBonusProcessor.process(any(), anyString(), any())).thenReturn(true);
        when(behaviorEventProcessor.process(any(), anyString(), any())).thenReturn(true);
        doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertTrue(future.get());

        assertEquals(2, calls.get());
        verify(auditFailProcessor, never()).handleFail(any(), anyString());
        verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());
    }

    @Test
    @DisplayName("业务判定环节返回false(真实违规) → 正常驳回且不重试")
    void testViolationReturnsFalseNoRetry() throws Exception {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
        // 真实违规：始终返回 false（若被错误重试会多次调用）
        doAnswer(invocation -> {
            AuditProcessorContext ctx = invocation.getArgument(2);
            ctx.putExtra("failReason", "涉政: 包含违规内容");
            return false;
        }).when(aiViolationProcessor).process(any(), anyString(), any());

        CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
        assertFalse(future.get());

        // 违规只调用 1 次：不重试，避免白耗 token
        verify(aiViolationProcessor).process(any(), anyString(), any());
        verify(auditFailProcessor).handleFail(any(), anyString());
        verify(imageScanProcessor, never()).process(any(), anyString(), any());
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
        verify(auditRecordService).record(any(), anyString(), eq(com.zhuri.coding.common.constants.ArticleConstants.AUDIT_STATUS_PASS), anyString());
    }

    // ==================== 修订审核（autoScanRevision） ====================

    @Test
    @DisplayName("修订审核：跳过 acceptRevision=false 的处理器，全通过后应用修订")
    void testRevisionSkipsNewPublishProcessors() throws Exception {
        // 用真实 PowerBonusProcessor / BehaviorEventProcessor（其 acceptRevision 覆写为 false），
        // 协作者用 mock 以便断言"被过滤、零交互"
        LevelService levelService = mock(LevelService.class);
        PowerBonusProcessor powerBonus = new PowerBonusProcessor(
                levelService, mock(ApArticleConfigMapper.class), mock(QualityNotificationProcessor.class));
        BehaviorEventBus behaviorEventBus = mock(BehaviorEventBus.class);
        BehaviorEventProcessor behaviorEvent = new BehaviorEventProcessor(behaviorEventBus);

        // 内容安全类处理器（参与修订）用 mock 表示（mock 的 acceptRevision 默认 false，需显式置 true）
        ArticleAuditProcessor contentSafe = mock(ArticleAuditProcessor.class);
        when(contentSafe.acceptRevision()).thenReturn(true);
        when(contentSafe.process(any(), anyString(), any())).thenReturn(true);

        ArticleAutoScanServiceImpl svc = new ArticleAutoScanServiceImpl(apArticleMapper, apArticleContentMapper,
                List.of(contentSafe, powerBonus, behaviorEvent),
                auditFailProcessor, articleTaskService, auditRecordService);
        ReflectionTestUtils.setField(svc, "apArticleDraftMapper", apArticleDraftMapper);
        ReflectionTestUtils.setField(svc, "articleRevisionService", articleRevisionService);

        ApArticle published = new ApArticle();
        published.setId(TEST_ARTICLE_ID);
        published.setAuthorId(TEST_AUTHOR_ID);
        published.setStatus(Status.PUBLISHED.getCode());
        published.setPendingRevisionId(999L);
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(published);
        ApArticleDraft draft = new ApArticleDraft();
        draft.setId(999L);
        draft.setContent("修订后的正文内容");
        when(apArticleDraftMapper.selectById(999L)).thenReturn(draft);

        CompletableFuture<Boolean> future = svc.autoScanRevision(TEST_ARTICLE_ID);
        assertTrue(future.get());

        // 参与修订的处理器被调用；"新发布语义"处理器被过滤跳过（其协作者零交互）
        verify(contentSafe).process(any(), anyString(), any());
        verifyNoInteractions(levelService);
        verifyNoInteractions(behaviorEventBus);
        // 全部通过 → 应用修订并写入 PASS 审计轨迹
        verify(articleRevisionService).applyRevision(TEST_ARTICLE_ID);
        verify(auditRecordService).record(any(), anyString(),
                eq(com.zhuri.coding.common.constants.ArticleConstants.AUDIT_STATUS_PASS), anyString());
    }
}
