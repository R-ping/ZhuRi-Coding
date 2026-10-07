package com.zhuri.coding.search.feign;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import com.zhuri.coding.search.service.ArticleSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SearchClient 测试。
 *
 * <p>它虽然放在 feign 包下、实现的是 {@code ISearchClient}，但运行时是 search 服务
 * 暴露给内部调用的 {@code @RestController}（索引同步 / RAG 召回 / 索引对账 / 下架移除）。
 * 这里钉住四件事：三个方法原样透传，以及 {@code missingArticleIds} 会多包一层
 * ResponseResult —— 因为底层返回裸 {@code List<Long>}，而内部接口契约是统一的响应体。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchClient 内部接口实现")
class SearchClientTest {

    @Mock
    private ArticleSearchService articleSearchService;

    @InjectMocks
    private SearchClient searchClient;

    @Test
    @DisplayName("同步文章 → 透传 syncArticle")
    void delegatesSyncArticle() {
        SearchArticleVo vo = new SearchArticleVo();
        vo.setId(1L);
        when(articleSearchService.syncArticle(any(SearchArticleVo.class)))
                .thenReturn(ResponseResult.okResult());

        assertNotNull(searchClient.syncArticle(vo));

        verify(articleSearchService).syncArticle(vo);
    }

    @Test
    @DisplayName("BM25 召回 → 透传 bm25Recall")
    void delegatesBm25Recall() {
        Bm25RecallDto dto = new Bm25RecallDto();
        dto.setQuery("Java");
        when(articleSearchService.bm25Recall(any(Bm25RecallDto.class)))
                .thenReturn(ResponseResult.okResult());

        assertNotNull(searchClient.bm25Recall(dto));

        verify(articleSearchService).bm25Recall(dto);
    }

    @Test
    @DisplayName("索引对账 → 透传 missingArticleIds，并把裸 List 包成统一响应体")
    void wrapsMissingIdsIntoResponse() {
        List<Long> candidates = List.of(1L, 2L);
        when(articleSearchService.missingArticleIds(candidates)).thenReturn(List.of(2L));

        ResponseResult r = searchClient.missingArticleIds(candidates);

        assertNotNull(r);
        assertEquals(List.of(2L), r.getData());
        verify(articleSearchService).missingArticleIds(candidates);
    }

    @Test
    @DisplayName("索引移除 → 透传 removeArticleIndex")
    void delegatesRemoveArticleIndex() {
        when(articleSearchService.removeArticleIndex(9L))
                .thenReturn(ResponseResult.okResult());

        assertNotNull(searchClient.removeArticleIndex(9L));

        verify(articleSearchService).removeArticleIndex(9L);
    }
}
