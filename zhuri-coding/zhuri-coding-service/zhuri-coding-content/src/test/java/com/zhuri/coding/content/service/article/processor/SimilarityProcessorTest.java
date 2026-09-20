package com.zhuri.coding.content.service.article.processor;

import com.zhuri.coding.content.mapper.article.ApArticleConfigMapper;
import com.zhuri.coding.content.service.article.ArticleSimilarityService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SimilarityProcessor 单元测试
 *
 * 覆盖 L2 项修复：推荐状态写入改为幂等 upsert（insertOrUpdateRecommend），
 * 校验高/低相似度下 isRecommend 取值正确，不再走"先查后插"导致的并发重复行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.WARN)
class SimilarityProcessorTest {

    @Mock
    private ArticleSimilarityService articleSimilarityService;
    @Mock
    private ApArticleConfigMapper apArticleConfigMapper;

    @Test
    @DisplayName("process - 高相似度 -> isRecommend=false，走幂等 upsert")
    void processHighSimilarityMarksNotRecommend() {
        SimilarityProcessor processor = new SimilarityProcessor(articleSimilarityService, apArticleConfigMapper);

        ApArticle article = new ApArticle();
        article.setId(100L);
        Map<String, Object> similar = new HashMap<>();
        similar.put("isSimilar", Boolean.TRUE);
        similar.put("maxSimilarity", 0.95);
        when(articleSimilarityService.checkSimilarity(any(), any())).thenReturn(similar);
        when(apArticleConfigMapper.insertOrUpdateRecommend(any())).thenReturn(1);

        boolean result = processor.process(article, "some content", new AuditProcessorContext());

        assertTrue(result);
        verify(apArticleConfigMapper).insertOrUpdateRecommend(org.mockito.ArgumentMatchers.argThat(cfg ->
                cfg.getArticleId().equals(100L) && !cfg.getIsRecommend()
                        && cfg.getIsComment() && cfg.getIsForward()
                        && !cfg.getIsDown() && !cfg.getIsDelete()));
    }

    @Test
    @DisplayName("process - 低相似度 -> isRecommend=true")
    void processLowSimilarityMarksRecommend() {
        SimilarityProcessor processor = new SimilarityProcessor(articleSimilarityService, apArticleConfigMapper);

        ApArticle article = new ApArticle();
        article.setId(200L);
        Map<String, Object> notSimilar = new HashMap<>();
        notSimilar.put("isSimilar", Boolean.FALSE);
        when(articleSimilarityService.checkSimilarity(any(), any())).thenReturn(notSimilar);
        when(apArticleConfigMapper.insertOrUpdateRecommend(any())).thenReturn(1);

        processor.process(article, "content", new AuditProcessorContext());

        verify(apArticleConfigMapper).insertOrUpdateRecommend(org.mockito.ArgumentMatchers.argThat(cfg ->
                cfg.getArticleId().equals(200L) && cfg.getIsRecommend()));
    }

    @Test
    @DisplayName("process - 空内容跳过相似度检测,不落配置")
    void processEmptyContentSkipsSimilarity() {
        SimilarityProcessor processor = new SimilarityProcessor(articleSimilarityService, apArticleConfigMapper);

        ApArticle article = new ApArticle();
        article.setId(300L);

        boolean result = processor.process(article, null, new AuditProcessorContext());

        assertTrue(result);
        org.mockito.Mockito.verify(apArticleConfigMapper, org.mockito.Mockito.never())
                .insertOrUpdateRecommend(any());
    }

    @Test
    @DisplayName("process - 相似度服务异常抛出AuditRetryableException供责任链重试,不落配置")
    void processSimilarityExceptionThrowsRetryable() {
        SimilarityProcessor processor = new SimilarityProcessor(articleSimilarityService, apArticleConfigMapper);

        ApArticle article = new ApArticle();
        article.setId(5L);
        when(articleSimilarityService.checkSimilarity(any(), any()))
                .thenThrow(new RuntimeException("es down"));

        // 相似度服务异常不再静默忽略，需抛可重试异常交由审核责任链按阶段重试
        assertThrows(AuditRetryableException.class,
                () -> processor.process(article, "content", new AuditProcessorContext()));

        // 异常时不写入推荐配置
        verify(apArticleConfigMapper, never()).insertOrUpdateRecommend(any());
    }
}