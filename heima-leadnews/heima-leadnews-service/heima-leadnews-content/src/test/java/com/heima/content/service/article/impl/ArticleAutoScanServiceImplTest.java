package com.heima.content.service.article.impl;

import com.heima.common.aliyun.GreenImageScanPlus;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.article.ApArticleAuditRecordMapper;
import com.heima.content.mapper.article.ApArticleConfigMapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleSimilarityService;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.content.service.article.BailianAiService;
import com.heima.content.service.level.LevelService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleAuditRecord;
import com.heima.model.article.pojos.ApArticleConfig;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ApArticleDraft;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ArticleAutoScanServiceImpl 单元测试
 *
 * 覆盖核心审核流程的所有分支路径：
 * - 正常审核通过流程
 * - AI违规检测失败
 * - 图片审核失败
 * - RAG高相似度检测
 * - 异常降级处理
 * - 逐力值加成与自动推荐
 */
@ExtendWith(MockitoExtension.class)
class ArticleAutoScanServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApArticleContentMapper apArticleContentMapper;
    @Mock
    private ApArticleConfigMapper apArticleConfigMapper;
    @Mock
    private GreenImageScanPlus greenImageScan;
    @Mock
    private BailianAiService bailianAiService;
    @Mock
    private ArticleSimilarityService articleSimilarityService;
    @Mock
    private LevelService levelService;
    @Mock
    private ArticleTaskService articleTaskService;
    @Mock
    private ApArticleAuditRecordMapper apArticleAuditRecordMapper;
    @Mock
    private BehaviorEventBus behaviorEventBus;

    @InjectMocks
    private ArticleAutoScanServiceImpl autoScanService;

    @Captor
    private ArgumentCaptor<ApArticle> articleCaptor;
    @Captor
    private ArgumentCaptor<ApArticleAuditRecord> auditRecordCaptor;
    @Captor
    private ArgumentCaptor<ApArticleConfig> configCaptor;

    private ApArticle normalArticle;
    private ApArticleContent articleContent;
    private static final Long TEST_ARTICLE_ID = 10001L;
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

    // ==================== 辅助方法 ====================

    private void mockBasicDependencies() {
        lenient().when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
        lenient().when(apArticleContentMapper.selectOne(any())).thenReturn(articleContent);
    }

    private void mockAiCheckPass() {
        Map<String, Object> result = new HashMap<>();
        result.put("is_violation", false);
        lenient().when(bailianAiService.checkViolation(any(), anyString())).thenReturn(result);
    }

    private void mockAiCheckFail(String type, String reason) {
        Map<String, Object> result = new HashMap<>();
        result.put("is_violation", true);
        result.put("violation_type", type);
        result.put("violation_reason", reason);
        lenient().when(bailianAiService.checkViolation(any(), anyString())).thenReturn(result);
    }

    private void mockImageScanPass() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("level", "pass");
        lenient().doReturn(result).when(greenImageScan).imageScan(anyString());
    }

    private void mockImageScanFail(String level) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("level", level);
        lenient().doReturn(result).when(greenImageScan).imageScan(anyString());
    }

    private void mockAiAnalysisPass(Integer qualityScore) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("qualityScore", qualityScore);
        lenient().when(bailianAiService.analyzeArticle(any(), anyString())).thenReturn(result);
    }

    private void mockSimilarityNotSimilar() {
        Map<String, Object> result = new HashMap<>();
        result.put("isSimilar", false);
        lenient().when(articleSimilarityService.checkSimilarity(any(), anyString())).thenReturn(result);
    }

    private void mockSimilarityHigh() {
        Map<String, Object> result = new HashMap<>();
        result.put("isSimilar", true);
        result.put("maxSimilarity", 0.92);
        lenient().when(articleSimilarityService.checkSimilarity(any(), anyString())).thenReturn(result);
    }

    // ==================== 测试用例 ====================

    @Nested
    @DisplayName("前置检查")
    class PreCheckTests {

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
        }
    }

    @Nested
    @DisplayName("AI违规检测")
    class AiViolationCheckTests {

        @Test
        @DisplayName("AI违规检测通过后继续后续流程")
        void testAiCheckPass() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            // 模拟已有配置（使updateArticleConfig调用updateById）
            ApArticleConfig existingConfig = new ApArticleConfig(TEST_ARTICLE_ID);
            when(apArticleConfigMapper.selectOne(any())).thenReturn(existingConfig);
            // 图片审核通过
            mockImageScanPass();
            // AI分析通过
            mockAiAnalysisPass(85);
            // 相似度不相似
            mockSimilarityNotSimilar();
            // 逐力值计算
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            // 任务添加
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证文章配置被更新为推荐
            verify(apArticleConfigMapper, atLeastOnce()).updateById(any(ApArticleConfig.class));
        }

        @Test
        @DisplayName("AI违规检测失败时文章状态变为FAIL")
        void testAiCheckFail() throws Exception {
            mockBasicDependencies();
            mockAiCheckFail("政治敏感", "文章包含违规政治内容");
            // 不处理图片/分析/相似度后续流程

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertFalse(future.get());

            // 验证文章状态更新为FAIL
            verify(apArticleMapper).updateById(articleCaptor.capture());
            ApArticle updated = articleCaptor.getValue();
            assertEquals(Status.FAIL.getCode(), updated.getStatus());
            assertTrue(updated.getReason().contains("政治敏感"));

            // 验证审计记录被写入
            verify(apArticleAuditRecordMapper).insert(auditRecordCaptor.capture());
            ApArticleAuditRecord record = auditRecordCaptor.getValue();
            assertEquals(TEST_ARTICLE_ID, record.getArticleId());
            assertEquals(TEST_AUTHOR_ID, record.getAuthorId());
            assertEquals(ArticleConstants.AUDIT_STATUS_FAIL, record.getStatus());
        }

        @Test
        @DisplayName("AI违规检测异常时降级通过")
        void testAiCheckException() throws Exception {
            mockBasicDependencies();
            when(bailianAiService.checkViolation(any(), anyString()))
                .thenThrow(new RuntimeException("AI服务超时"));
            // 后续正常流程
            mockImageScanPass();
            mockAiAnalysisPass(70);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 2);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            // 降级通过
            assertTrue(future.get());
        }
    }

    @Nested
    @DisplayName("图片审核")
    class ImageScanTests {

        @Test
        @DisplayName("图片审核high级别时文章状态变为FAIL")
        void testImageScanHigh() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            // 文章有图片
            ApArticleDraft.ContPic pic = new ApArticleDraft.ContPic();
            pic.setPicUrl("https://example.com/violation.png");
            normalArticle.setContPics(java.util.List.of(pic));
            mockImageScanFail("high");

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertFalse(future.get());

            verify(apArticleMapper).updateById(articleCaptor.capture());
            assertEquals(Status.FAIL.getCode(), articleCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("图片审核medium级别时文章状态变为FAIL")
        void testImageScanMedium() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            ApArticleDraft.ContPic pic = new ApArticleDraft.ContPic();
            pic.setPicUrl("https://example.com/uncertain.png");
            normalArticle.setContPics(java.util.List.of(pic));
            mockImageScanFail("medium");

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertFalse(future.get());

            verify(apArticleMapper).updateById(articleCaptor.capture());
            assertEquals(Status.FAIL.getCode(), articleCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("图片审核通过后继续后续流程")
        void testImageScanPass() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            ApArticleDraft.ContPic pic = new ApArticleDraft.ContPic();
            pic.setPicUrl("https://example.com/normal.png");
            normalArticle.setContPics(java.util.List.of(pic));
            mockImageScanPass();
            mockAiAnalysisPass(75);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());
        }

        @Test
        @DisplayName("封面图片也参与审核")
        void testCoverImageAlsoScanned() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            normalArticle.setCoverImage("https://example.com/cover.png");
            mockImageScanFail("high");

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertFalse(future.get());
        }
    }

    @Nested
    @DisplayName("RAG相似度检测")
    class SimilarityCheckTests {

        @Test
        @DisplayName("高相似度文章标记为不推荐")
        void testHighSimilarityMarksNotRecommend() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(80);
            mockSimilarityHigh();
            // 模拟已有配置
            ApArticleConfig existingConfig = new ApArticleConfig(TEST_ARTICLE_ID);
            when(apArticleConfigMapper.selectOne(any())).thenReturn(existingConfig);
            // 逐力值达到4级
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 4);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证配置更新：先设为不推荐(false)，后因等级达标又设为推荐(true)
            verify(apArticleConfigMapper, atLeast(2)).updateById(configCaptor.capture());
            // 最后调用 isRecommend=true（因为等级4达到自动推荐）
            ApArticleConfig lastConfig = configCaptor.getValue();
            assertTrue(lastConfig.getIsRecommend());
        }
    }

    @Nested
    @DisplayName("AI内容分析与逐力值计算")
    class AiAnalysisAndPowerTests {

        @Test
        @DisplayName("AI分析异常时降级通过，不计算逐力值")
        void testAiAnalysisException() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            when(bailianAiService.analyzeArticle(any(), anyString()))
                .thenThrow(new RuntimeException("AI分析服务异常"));
            mockSimilarityNotSimilar();
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 不应调用逐力值计算
            verify(levelService, never()).calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("AI质量评分优秀(≥80)时逐力值+3并发送通知")
        void testExcellentQualityBonus() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(90);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(eq(TEST_AUTHOR_ID), eq(TEST_ARTICLE_ID),
                eq("publish_article"), eq(3)))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证逐力值+3
            verify(levelService).calculatePowerWithLimit(TEST_AUTHOR_ID, TEST_ARTICLE_ID, "publish_article", 3);
        }

        @Test
        @DisplayName("AI质量评分合格(60-79)时逐力值+1")
        void testPassQualityBonus() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(65);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 2);
            when(levelService.calculatePowerWithLimit(eq(TEST_AUTHOR_ID), eq(TEST_ARTICLE_ID),
                eq("publish_article"), eq(1)))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证逐力值+1
            verify(levelService).calculatePowerWithLimit(TEST_AUTHOR_ID, TEST_ARTICLE_ID, "publish_article", 1);
        }

        @Test
        @DisplayName("AI质量评分不合格(<60)时不加逐力值")
        void testLowQualityNoBonus() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(45);
            mockSimilarityNotSimilar();
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 不应调用逐力值计算（powerBonus=0，不满足>0条件）
            verify(levelService, never()).calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("逐力值达到4级时自动推荐到首页")
        void testLevel4AutoRecommend() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(88);
            mockSimilarityHigh(); // 高相似度，先设为不推荐
            // 模拟已有配置
            ApArticleConfig existingConfig = new ApArticleConfig(TEST_ARTICLE_ID);
            when(apArticleConfigMapper.selectOne(any())).thenReturn(existingConfig);
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 4);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证最终isRecommend=true（等级4覆盖了高相似度的不推荐标记）
            verify(apArticleConfigMapper, atLeast(2)).updateById(configCaptor.capture());
            assertTrue(configCaptor.getValue().getIsRecommend());
        }
    }

    @Nested
    @DisplayName("行为事件总线")
    class BehaviorEventBusTests {

        @Test
        @DisplayName("审核通过时触发发布文章行为事件")
        void testBehaviorEventTriggered() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(80);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            when(behaviorEventBus.execute(any())).thenReturn(ResponseResult.okResult(null));
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证行为事件被触发
            ArgumentCaptor<BehaviorContext> contextCaptor = ArgumentCaptor.forClass(BehaviorContext.class);
            verify(behaviorEventBus).execute(contextCaptor.capture());
            BehaviorContext ctx = contextCaptor.getValue();
            assertEquals(BehaviorType.PUBLISH_ARTICLE, ctx.getBehaviorType());
            assertEquals(TEST_AUTHOR_ID.intValue(), ctx.getUserId().intValue());
            assertEquals(TEST_ARTICLE_ID, ctx.getTargetId());
        }

        @Test
        @DisplayName("behaviorEventBus为null时跳过不影响审核")
        void testBehaviorEventBusNull() throws Exception {
            // 重新创建实例，behaviorEventBus=null
            ArticleAutoScanServiceImpl service = new ArticleAutoScanServiceImpl();
            setField(service, "apArticleMapper", apArticleMapper);
            setField(service, "apArticleContentMapper", apArticleContentMapper);
            setField(service, "apArticleConfigMapper", apArticleConfigMapper);
            setField(service, "bailianAiService", bailianAiService);
            setField(service, "articleSimilarityService", articleSimilarityService);
            setField(service, "levelService", levelService);
            setField(service, "articleTaskService", articleTaskService);
            setField(service, "apArticleAuditRecordMapper", apArticleAuditRecordMapper);
            setField(service, "behaviorEventBus", null);

            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(80);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = service.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());
        }

        private void setField(Object target, String fieldName, Object value) {
            try {
                java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("完整审核流程集成")
    class FullFlowTests {

        @Test
        @DisplayName("完整正常流程：审核通过并添加延迟任务")
        void testFullPassFlow() throws Exception {
            mockBasicDependencies();
            mockAiCheckPass();
            mockImageScanPass();
            mockAiAnalysisPass(85);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            when(behaviorEventBus.execute(any())).thenReturn(ResponseResult.okResult(null));
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 验证关键交互
            verify(apArticleMapper).selectById(TEST_ARTICLE_ID);
            verify(bailianAiService).checkViolation(any(), anyString());
            verify(bailianAiService).analyzeArticle(any(), anyString());
            verify(articleSimilarityService).checkSimilarity(any(), anyString());
            verify(levelService).calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt());
            verify(behaviorEventBus).execute(any());
            verify(articleTaskService).addArticleToTask(eq(TEST_ARTICLE_ID), any());

            // 验证文章状态未变（仍为SUBMIT，因为状态在发布时由TaskService更新）
            // 使用any()匹配所有updateById调用，统计调用次数后验证
            verify(apArticleMapper, never()).updateById(any(ApArticle.class));
        }

        @Test
        @DisplayName("内容为空时仍能正常处理")
        void testEmptyContent() throws Exception {
            when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(normalArticle);
            ApArticleContent emptyContent = new ApArticleContent();
            emptyContent.setArticleId(TEST_ARTICLE_ID);
            emptyContent.setContent("");
            when(apArticleContentMapper.selectOne(any())).thenReturn(emptyContent);

            mockAiCheckPass();
            // 无内容，跳过图片审核
            mockAiAnalysisPass(80);
            mockSimilarityNotSimilar();
            Map<String, Object> powerResult = new HashMap<>();
            powerResult.put("success", true);
            powerResult.put("newLevel", 3);
            when(levelService.calculatePowerWithLimit(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(powerResult);
            doNothing().when(articleTaskService).addArticleToTask(anyLong(), any());

            CompletableFuture<Boolean> future = autoScanService.autoScanArticle(TEST_ARTICLE_ID);
            assertTrue(future.get());

            // 空内容时跳过图片审核
            verify(greenImageScan, never()).imageScan(anyString());
        }
    }
}