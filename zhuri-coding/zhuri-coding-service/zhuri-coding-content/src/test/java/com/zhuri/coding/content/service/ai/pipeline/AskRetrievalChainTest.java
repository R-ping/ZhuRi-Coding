package com.zhuri.coding.content.service.ai.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.HybridRecallService;
import com.zhuri.coding.content.service.ai.HybridRecallService.Recall;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.article.dtos.AiSourceVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AskRetrievalChain 单元测试（P2 Prompt Chaining：向量化 → 混合召回 → 过滤 → LLM Rerank → 组装）。
 *
 * <p>覆盖：五阶段顺序执行与 hits 口径（=候选数）；Rerank 生效时按 selected 排序 + topK 截断；
 * Rerank 关闭时不调用 LLM；向量化失败整体返回 null；无候选/过滤后为空返回 hits=0 空结果；
 * 召回异常 fail-open；BM25 独有命中经本地余弦补全相似度。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AskRetrievalChain（RAG 检索显式链）")
class AskRetrievalChainTest {

    private static final double[] VEC = {0.1, 0.2, 0.3, 0.4};

    @Mock private ArticleEmbeddingServiceImpl embeddingService;
    @Mock private HybridRecallService hybridRecallService;
    @Mock private AiLlmGateway llmGateway;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper contentMapper;

    private AskRetrievalChain chain;

    @BeforeEach
    void setUp() {
        // promptRegistry 传 null：解析走代码兜底（version=0），与单测口径一致
        chain = new AskRetrievalChain(embeddingService, hybridRecallService, llmGateway, null,
            apArticleMapper, contentMapper);
    }

    private ApArticle article(Long id, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setAuthorName("作者" + id);
        a.setStatus(Status.PUBLISHED.getCode());
        a.setIsAigc(0);
        a.setLikes(10);
        return a;
    }

    private AskRetrievalChain.ChainCtx ctx(boolean doRerank) {
        return new AskRetrievalChain.ChainCtx("查询词", "用户问题", 3, doRerank, 15, 4, 200, "rerank兜底prompt");
    }

    private AskRetrievalChain.ChainCtx ctx(Long anchorArticleId, boolean useChunkContext) {
        return new AskRetrievalChain.ChainCtx("查询词", "用户问题", 3, false, 15, 4, 200,
            "rerank兜底prompt", anchorArticleId, true, useChunkContext, 2, null);
    }

    private void stubRecall(List<Long> ids, Map<Long, Double> sims) {
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(hybridRecallService.recall(anyString(), any(double[].class), anyInt()))
            .thenReturn(new Recall(ids, sims));
    }

    // ==================== 主链路 ====================

    @Test
    @DisplayName("五阶段顺序执行：hits=候选数，资料来源与文章一一对应")
    void fullSequenceBuildsResult() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        Map<Long, Double> sims = Map.of(1L, 0.9, 2L, 0.8, 3L, 0.7, 4L, 0.6);
        stubRecall(ids, sims);
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(
            article(1L, "标题1"), article(2L, "标题2"), article(3L, "标题3"), article(4L, "标题4")));
        when(contentMapper.selectList(any())).thenReturn(List.of(contentOf(1L), contentOf(2L), contentOf(3L), contentOf(4L)));
        // rerank 关闭：不触发 LLM，稳定验证链自身口径

        AskRetrievalChain.ChainResult r = chain.run(ctx(false));

        assertNotNull(r);
        assertEquals(4, r.hits(), "hits 口径 = 实际候选数（与原实现一致）");
        assertEquals(3, r.sources().size(), "未开 rerank 时按召回序取前 topK=3 篇");

        // docsText 覆盖标题与正文截断
        assertTrue(r.docsText().contains("标题1"));
        assertTrue(r.docsText().contains("作者1"));
        assertTrue(r.docsText().contains("【正文1】"));
        // sources 相似度 = 召回阶段向量相似度
        AiSourceVo first = r.sources().get(0);
        assertEquals(1L, first.getArticleId());
        assertEquals("作者1", first.getAuthor());
        assertEquals(0.9, first.getSimilarity());
        // 不启用 rerank → LLM 不参与
        verify(llmGateway, never()).generateOrNull(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Rerank 生效：按 selected 排序（[4,1] → 文章4、文章1），topK 截断")
    void rerankReordersAndTruncates() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        stubRecall(ids, Map.of(1L, 0.9, 2L, 0.8, 3L, 0.7, 4L, 0.6));
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(
            article(1L, "标题1"), article(2L, "标题2"), article(3L, "标题3"), article(4L, "标题4")));
        when(contentMapper.selectList(any())).thenReturn(List.of(contentOf(1L), contentOf(4L)));
        when(llmGateway.generateOrNull(eq(AiFeatures.RERANK), anyString(), anyString(), any(), any()))
            .thenReturn("{\"selected\":[4,1]}");

        AskRetrievalChain.ChainResult r = chain.run(ctx(true));

        assertNotNull(r);
        // selected [4,1] 且都不超过 topK=3 → 保留全部
        assertEquals(2, r.sources().size());
        assertEquals(4L, r.sources().get(0).getArticleId());
        assertEquals(1L, r.sources().get(1).getArticleId());
        assertEquals(4, r.hits(), "hits 仍为候选数，不受 rerank 截断影响");
        verify(llmGateway).generateOrNull(eq(AiFeatures.RERANK), anyString(), anyString(), any(), any());
    }

    // ==================== fail-open ====================

    @Test
    @DisplayName("向量化失败：链返回 null（调用方降级）")
    void embeddingFailureReturnsNull() {
        when(embeddingService.generateEmbedding(anyString())).thenReturn(null);

        assertNull(chain.run(ctx(false)));
    }

    @Test
    @DisplayName("候选为空：返回 hits=0 空结果（无来源）")
    void noCandidatesReturnsEmpty() {
        stubRecall(List.of(), Map.of());

        AskRetrievalChain.ChainResult r = chain.run(ctx(false));

        assertNotNull(r);
        assertEquals(0, r.hits());
        assertTrue(r.sources().isEmpty());
        assertEquals("", r.docsText());
    }

    @Test
    @DisplayName("召回异常 fail-open：按无候选处理，不向上抛")
    void recallExceptionFailsOpen() {
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(hybridRecallService.recall(anyString(), any(double[].class), anyInt()))
            .thenThrow(new RuntimeException("recall down"));

        AskRetrievalChain.ChainResult r = chain.run(ctx(false));

        assertNotNull(r);
        assertEquals(0, r.hits());
        assertTrue(r.sources().isEmpty());
    }

    @Test
    @DisplayName("过滤后为空（候选均未发布/AIGC）：hits=0（上游返回空答案，不外漏未发布内容）")
    void filterDropsAll() {
        List<Long> ids = List.of(7L, 8L);
        stubRecall(ids, Map.of(7L, 0.9, 8L, 0.8));
        ApArticle draft = article(7L, "草稿不过审");
        draft.setStatus((byte) 0);
        ApArticle aigc = article(8L, "AI 生成不参与 RAG");
        aigc.setIsAigc(1);
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(draft, aigc));

        AskRetrievalChain.ChainResult r = chain.run(ctx(false));

        assertNotNull(r);
        assertEquals(0, r.hits());
        assertTrue(r.sources().isEmpty());
    }

    @Test
    @DisplayName("作者删除的文章不能当引用源：status 仍是已发布，只有 is_deleted 能拦住")
    void filterDropsAuthorDeleted() {
        List<Long> ids = List.of(9L);
        stubRecall(ids, Map.of(9L, 0.9));
        ApArticle deleted = article(9L, "作者已删除");
        deleted.setIsDeleted(true);
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(deleted));

        AskRetrievalChain.ChainResult r = chain.run(ctx(false));

        assertNotNull(r);
        assertEquals(0, r.hits());
        assertTrue(r.sources().isEmpty());
    }

    @Test
    @DisplayName("锚点命中召回：移到首位并标记 anchor")
    void anchorRecalledMovesFirst() {
        List<Long> ids = List.of(1L, 2L, 3L);
        stubRecall(ids, Map.of(1L, 0.9, 2L, 0.85, 3L, 0.8));
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(
            article(1L, "标题1"), article(2L, "标题2"), article(3L, "标题3")));
        when(contentMapper.selectList(any())).thenReturn(List.of(
            contentOf(1L), contentOf(2L), contentOf(3L)));

        AskRetrievalChain.ChainResult r = chain.run(ctx(2L, false));

        assertNotNull(r);
        assertEquals(2L, r.sources().get(0).getArticleId());
        assertTrue(r.sources().get(0).getAnchor());
        assertEquals(1L, r.sources().get(1).getArticleId());
    }

    @Test
    @DisplayName("锚点未被召回：不强制注入，按普通全局问答返回")
    void anchorNotRecalledNotInjected() {
        List<Long> ids = List.of(1L, 2L);
        stubRecall(ids, Map.of(1L, 0.9, 2L, 0.8));
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(
            article(1L, "标题1"), article(2L, "标题2")));
        when(contentMapper.selectList(any())).thenReturn(List.of(contentOf(1L), contentOf(2L)));

        AskRetrievalChain.ChainResult r = chain.run(ctx(99L, false));

        assertNotNull(r);
        assertEquals(2, r.sources().size());
        assertFalse(r.sources().stream().anyMatch(s -> Boolean.TRUE.equals(s.getAnchor())));
    }

    @Test
    @DisplayName("子块上下文：命中块进入 prompt，而不是文章开头截断")
    void chunkContextUsesMatchedChunk() {
        List<Long> ids = List.of(1L);
        stubRecall(ids, Map.of(1L, 0.9));
        when(apArticleMapper.selectBatchIds(ids)).thenReturn(List.of(article(1L, "标题1")));
        when(contentMapper.selectList(any())).thenReturn(List.of(contentOf(1L)));
        when(embeddingService.findTopChunks(any(), any(double[].class), anyInt()))
            .thenReturn(Map.of(1L, List.of(
                new ArticleEmbeddingServiceImpl.ChunkHit(1L, 3, "命中块正文-后半段", 0.88))));

        AskRetrievalChain.ChainResult r = chain.run(ctx(null, true));

        assertNotNull(r);
        assertTrue(r.docsText().contains("命中块正文-后半段"));
        assertFalse(r.docsText().contains("【正文1】"), "有子块时不应回退到整篇开头截断");
    }

    private ApArticleContent contentOf(Long articleId) {        ApArticleContent c = new ApArticleContent();
        c.setArticleId(articleId);
        c.setContent("【正文" + articleId + "】此处为正文内容，用于组装参考资料。");
        return c;
    }
}