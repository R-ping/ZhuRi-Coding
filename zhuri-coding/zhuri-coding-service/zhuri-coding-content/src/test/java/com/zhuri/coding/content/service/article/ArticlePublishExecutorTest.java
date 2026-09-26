package com.zhuri.coding.content.service.article;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticlePublishExecutor 单元测试 —— 覆盖"置位 DB 发布态 + 同步 ES"这段被抽出的业务逻辑。
 *
 * <p>抽出来之后，这段逻辑**只在这里测一遍**，两条链路（旧 {@code article_event} 状态机、
 * 新 Outbox Handler）的状态机语义则在各自的测试里覆盖。这就是"按职责切分测试"的收益：
 * 业务逻辑改动不会让状态机测试整片失败，反之亦然。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章发布执行体（置位 + ES 同步）")
class ArticlePublishExecutorTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ISearchClient searchClient;

    @InjectMocks
    private ArticlePublishExecutor executor;

    private ApArticle article(ApArticle.Status status) {
        ApArticle a = new ApArticle();
        a.setId(1L);
        a.setStatus((byte) status.getCode());
        return a;
    }

    @Test
    @DisplayName("置位成功 → DONE 并同步 ES")
    void markSuccessThenSync() {
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(1);

        assertEquals(ArticlePublishExecutor.Outcome.DONE, executor.publish(1L));
        verify(searchClient).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("置位 0 行但文章已是发布态 → 幂等续跑，仍同步 ES")
    void alreadyPublishedContinues() {
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        when(apArticleMapper.selectById(1L)).thenReturn(article(ApArticle.Status.PUBLISHED));

        assertEquals(ArticlePublishExecutor.Outcome.DONE, executor.publish(1L));
        verify(searchClient).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("置位 0 行且文章仍待审 → STILL_PENDING（不碰 ES）")
    void stillPending() {
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        when(apArticleMapper.selectById(1L)).thenReturn(article(ApArticle.Status.SUBMIT));

        assertEquals(ArticlePublishExecutor.Outcome.STILL_PENDING, executor.publish(1L));
        verify(searchClient, never()).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("文章不存在 → ARTICLE_MISSING（不碰 ES）")
    void articleMissing() {
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        when(apArticleMapper.selectById(1L)).thenReturn(null);

        assertEquals(ArticlePublishExecutor.Outcome.ARTICLE_MISSING, executor.publish(1L));
        verify(searchClient, never()).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("文章处不可发布终态 → ARTICLE_NOT_PUBLISHABLE（不碰 ES）")
    void notPublishable() {
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        when(apArticleMapper.selectById(1L)).thenReturn(article(ApArticle.Status.FAIL));

        assertEquals(ArticlePublishExecutor.Outcome.ARTICLE_NOT_PUBLISHABLE, executor.publish(1L));
        verify(searchClient, never()).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("ES 同步失败 → 抛出异常，由调用方决定「计次与否」")
    void esSyncFailurePropagates() {
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(1);
        doThrow(new RuntimeException("es down")).when(searchClient).syncArticle(any(SearchArticleVo.class));

        assertThrows(RuntimeException.class, () -> executor.publish(1L));
    }
}
