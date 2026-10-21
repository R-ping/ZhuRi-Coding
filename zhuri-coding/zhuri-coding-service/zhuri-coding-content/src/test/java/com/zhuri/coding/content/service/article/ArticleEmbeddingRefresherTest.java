package com.zhuri.coding.content.service.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 文章向量新鲜度处理单测。
 *
 * <p>钉住三条判定：缺的补、指纹不一致的重算、一致的不必烧 embedding；
 * 以及「不该有向量」的两种状态（未发布 / 已判水文）—— 前者跳过，后者顺手清理残留。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章向量新鲜度刷新")
class ArticleEmbeddingRefresherTest {

    private static final double[] VEC = {0.1, 0.2, 0.3};
    private static final String BODY = "Redis 分布式锁的实现与续期机制。";

    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper apArticleContentMapper;
    @Mock private ArticleEmbeddingServiceImpl embeddingService;

    @InjectMocks private ArticleEmbeddingRefresher refresher;

    @Test
    @DisplayName("未发布 / 已删除的文章不需要向量：直接跳过")
    void skipsInvisibleArticle() {
        ApArticle draft = article(ApArticle.Status.SUBMIT.getCode(), 0);
        assertFalse(refresher.refreshIfStale(draft).changed());

        ApArticle deleted = article(ApArticle.Status.PUBLISHED.getCode(), 0);
        deleted.setIsDeleted(true);
        assertFalse(refresher.refreshIfStale(deleted).changed());

        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("正文缺失（数据异常）：不动已有向量")
    void skipsWhenContentMissing() {
        when(apArticleContentMapper.selectOne(any())).thenReturn(null);

        assertFalse(refresher.refreshIfStale(article(ApArticle.Status.PUBLISHED.getCode(), 0)).changed());

        verify(embeddingService, never()).deleteEmbedding(any());
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("缺向量与分块：补齐，并把当前正文指纹一并落库")
    void fillsMissingVectors() {
        when(apArticleContentMapper.selectOne(any())).thenReturn(content(BODY));
        when(embeddingService.getEmbeddingMeta(9L)).thenReturn(null);
        when(embeddingService.getChunksMeta(9L)).thenReturn(null);
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(embeddingService.getChunkMaxPerArticle()).thenReturn(20);

        ArticleEmbeddingRefresher.Outcome outcome =
                refresher.refreshIfStale(article(ApArticle.Status.PUBLISHED.getCode(), 0));

        assertTrue(outcome.articleWritten);
        assertFalse(outcome.articleRefreshed);
        assertTrue(outcome.chunksWritten);
        String expectedHash = ArticleEmbeddingServiceImpl.contentHash(BODY);
        verify(embeddingService).saveEmbedding(eq(9L), eq(VEC), eq(expectedHash), any());
        verify(embeddingService).saveChunks(eq(9L), anyList(), eq(expectedHash), any());
    }

    @Test
    @DisplayName("正文改过（指纹不一致）：重算，而不是新增")
    void refreshesStaleVector() {
        when(apArticleContentMapper.selectOne(any())).thenReturn(content(BODY));
        when(embeddingService.getEmbeddingMeta(9L))
                .thenReturn(new ArticleEmbeddingServiceImpl.EmbeddingMeta("旧指纹", null));
        when(embeddingService.getChunksMeta(9L))
                .thenReturn(new ArticleEmbeddingServiceImpl.EmbeddingMeta("旧指纹", null));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(embeddingService.getChunkMaxPerArticle()).thenReturn(20);

        ArticleEmbeddingRefresher.Outcome outcome =
                refresher.refreshIfStale(article(ApArticle.Status.PUBLISHED.getCode(), 0));

        assertTrue(outcome.articleRefreshed);
        assertFalse(outcome.articleWritten);
        assertTrue(outcome.chunksWritten);
    }

    @Test
    @DisplayName("指纹一致：跳过，不做无谓的 embedding 调用")
    void skipsUpToDateVector() {
        String hash = ArticleEmbeddingServiceImpl.contentHash(BODY);
        when(apArticleContentMapper.selectOne(any())).thenReturn(content(BODY));
        when(embeddingService.getEmbeddingMeta(9L))
                .thenReturn(new ArticleEmbeddingServiceImpl.EmbeddingMeta(hash, null));
        when(embeddingService.getChunksMeta(9L))
                .thenReturn(new ArticleEmbeddingServiceImpl.EmbeddingMeta(hash, null));

        assertFalse(refresher.refreshIfStale(article(ApArticle.Status.PUBLISHED.getCode(), 0)).changed());

        verify(embeddingService, never()).generateEmbedding(anyString());
        verify(embeddingService, never()).saveEmbedding(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("已判 AIGC 水文：清掉残留向量，不重算")
    void purgesWatermarkedArticle() {
        String hash = ArticleEmbeddingServiceImpl.contentHash(BODY);
        when(apArticleContentMapper.selectOne(any())).thenReturn(content(BODY));
        when(embeddingService.getEmbeddingMeta(9L))
                .thenReturn(new ArticleEmbeddingServiceImpl.EmbeddingMeta(hash, null));
        when(embeddingService.getChunksMeta(9L))
                .thenReturn(new ArticleEmbeddingServiceImpl.EmbeddingMeta(hash, null));

        ArticleEmbeddingRefresher.Outcome outcome =
                refresher.refreshIfStale(article(ApArticle.Status.PUBLISHED.getCode(), 1));

        assertFalse(outcome.changed());
        verify(embeddingService).deleteEmbedding(9L);
        verify(embeddingService).deleteChunks(9L);
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("embedding 调用失败必须抛出：实时链路靠异常触发重试，不能静默吞掉")
    void propagatesFailureForRetry() {
        when(apArticleContentMapper.selectOne(any())).thenReturn(content(BODY));
        when(embeddingService.getEmbeddingMeta(9L)).thenReturn(null);
        when(embeddingService.getChunksMeta(9L)).thenReturn(null);
        when(embeddingService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        assertThrows(RuntimeException.class,
                () -> refresher.refreshIfStale(article(ApArticle.Status.PUBLISHED.getCode(), 0)));
    }

    @Test
    @DisplayName("按 id 刷新：文章不存在 / id 为空时静默跳过（可能已被物理删除）")
    void skipsMissingArticle() {
        when(apArticleMapper.selectById(9L)).thenReturn(null);

        assertFalse(refresher.refreshIfStale(9L).changed());
        assertFalse(refresher.refreshIfStale((Long) null).changed());
    }

    // ==================== helpers ====================

    private ApArticle article(byte status, int isAigc) {
        ApArticle article = new ApArticle();
        article.setId(9L);
        article.setStatus(status);
        article.setIsAigc(isAigc);
        article.setIsDeleted(false);
        return article;
    }

    private ApArticleContent content(String body) {
        ApArticleContent content = new ApArticleContent();
        content.setArticleId(9L);
        content.setContent(body);
        return content;
    }
}
