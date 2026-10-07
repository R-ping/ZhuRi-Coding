package com.zhuri.coding.search.service.impl;

import com.zhuri.coding.apis.article.IArticleClient;
import com.zhuri.coding.apis.article.ISemanticSearchClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.dtos.UserSearchDto;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import com.zhuri.coding.model.search.vos.TocItem;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.search.entity.SearchArticle;
import com.zhuri.coding.search.service.ApAssociateWordsService;
import com.zhuri.coding.search.service.ApUserSearchService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ArticleSearchServiceImpl 单元测试（ES 文章检索 / 同步 / 召回 / 对账）
 *
 * 纯 @Service，依赖经 @Autowired 由 @InjectMocks 注入：
 * - ElasticsearchOperations 检索执行（search/save/delete）全部 Mock；
 * - ApAssociateWordsService 联想词计数；
 * - IArticleClient Feign 获取文章正文；
 * - ApUserSearchService 搜索历史、ISemanticSearchClient 向量兜底。
 *
 * 覆盖七条公共链路：面向用户的 search（含语义兜底与搜索历史）、syncArticle、
 * bm25Recall（RAG 召回）、missingArticleIds（索引对账）、removeArticleIndex（下架移除），
 * 以及各链路的异常分支，无需启动真实 ES/Feign/Mongo。
 *
 * <p>两条「失败必须抛出」的约定在此被钉住：{@code missingArticleIds} 抛出可避免对账
 * 把 ES 故障伪装成"索引一致"，{@code removeArticleIndex} 抛出可让调用方判断下架是否真的生效。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleSearchService ES 文章检索")
class ArticleSearchServiceImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ApAssociateWordsService apAssociateWordsService;

    @Mock
    private IArticleClient articleClient;

    @Mock
    private ApUserSearchService apUserSearchService;

    /** 语义召回兜底（@Autowired(required=false)，Spring 环境下可不装配） */
    @Mock
    private ISemanticSearchClient semanticSearchClient;

    @InjectMocks
    private ArticleSearchServiceImpl articleSearchService;

    // ==================== 检索 ====================

    @Nested
    @DisplayName("search - 文章检索")
    class Search {

        @Test
        @DisplayName("参数为空 → 参数非法")
        void testSearchNullDto() {
            ResponseResult r = articleSearchService.search(null);
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
            verify(apAssociateWordsService, never()).incrementSearchCount(anyString());
        }

        @Test
        @DisplayName("搜索词为空/空白 → 参数非法")
        void testSearchBlankWords() {
            ResponseResult r1 = articleSearchService.search(dto("", 1, 10));
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r1.getCode());

            ResponseResult r2 = articleSearchService.search(dto("   ", 1, 10));
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r2.getCode());

            verify(apAssociateWordsService, never()).incrementSearchCount(anyString());
        }

        @Test
        @DisplayName("检索成功：命中含高亮标题")
        void testSearchSuccessWithHighlight() {
            UserSearchDto dto = dto("Java", 2, 5);
            dto.setMinBehotTime(new Date());

            SearchArticle article = article(1L, "Java 入门");
            SearchHit<SearchArticle> hit = mockHit(article, List.of("<font>Java</font>"));
            SearchHits<SearchArticle> searchHits = hits(hit);

            when(elasticsearchOperations.search(any(NativeQuery.class), eq(SearchArticle.class)))
                    .thenReturn(searchHits);

            ResponseResult r = articleSearchService.search(dto);

            verify(apAssociateWordsService).incrementSearchCount("Java");
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
            assertEquals(1, list.size());
            Map<String, Object> m = list.get(0);
            // 高亮标题走 h_title 字段
            assertEquals("<font>Java</font>", m.get("h_title"));
            assertEquals("1", m.get("id"));
            assertEquals("Java 入门", m.get("title"));
        }

        @Test
        @DisplayName("检索成功：无高亮时回退原文标题")
        void testSearchSuccessFallbackTitle() {
            UserSearchDto dto = dto("Spring", 0, 0); // 页码/条数默认兜底

            SearchArticle article = article(2L, "Spring Boot");
            SearchHit<SearchArticle> hit = mockHit(article, null);
            SearchHits<SearchArticle> searchHits = hits(hit);
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(SearchArticle.class)))
                    .thenReturn(searchHits);

            ResponseResult r = articleSearchService.search(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
            assertEquals(1, list.size());
            assertEquals("Spring Boot", list.get(0).get("h_title"));
        }

        @Test
        @DisplayName("检索成功：空结果集")
        void testSearchEmpty() {
            SearchHits<SearchArticle> searchHits = hits();
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(SearchArticle.class)))
                    .thenReturn(searchHits);
            ResponseResult r = articleSearchService.search(dto("无结果", 1, 10));
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            assertEquals(0, ((List<?>) r.getData()).size());
        }
    }

    // ==================== 同步 ====================

    @Nested
    @DisplayName("syncArticle - 文章同步到 ES")
    class SyncArticle {

        @Test
        @DisplayName("入参为空 → 参数非法")
        void testSyncArticleNull() {
            ResponseResult r = articleSearchService.syncArticle(null);
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        }

        @Test
        @DisplayName("id 为空 → 参数非法")
        void testSyncArticleNullId() {
            SearchArticleVo vo = new SearchArticleVo();
            ResponseResult r = articleSearchService.syncArticle(vo);
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        }

        @Test
        @DisplayName("同步成功：连正文+目录，落 ES")
        void testSyncArticleSuccess() {
            SearchArticleVo vo = new SearchArticleVo();
            vo.setId(100L);
            vo.setTitle("标题");
            vo.setContent("正文");
            vo.setTocList(new ArrayList<>());
            TocItem toc = new TocItem();
            toc.setId("1");
            toc.setLevel(1);
            toc.setText("章节");
            vo.getTocList().add(toc);

            ResponseResult contentResult = ResponseResult.okResult(new HashMap<>());
            when(articleClient.getContent(100L)).thenReturn(contentResult);

            ResponseResult r = articleSearchService.syncArticle(vo);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            // 已写入 ES 且正文已回填
            verify(elasticsearchOperations).save(any(SearchArticle.class));
            assertNotNull(vo.getContent());
        }

        @Test
        @DisplayName("同步成功：Feign 无正文数据时跳过回填")
        void testSyncArticleWithoutContent() {
            SearchArticleVo vo = new SearchArticleVo();
            vo.setId(200L);
            vo.setTitle("无正文");
            when(articleClient.getContent(200L)).thenReturn(null);

            ResponseResult r = articleSearchService.syncArticle(vo);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            verify(elasticsearchOperations).save(any(SearchArticle.class));
        }

        @Test
        @DisplayName("同步失败：Feign 调用异常 → 服务端错误")
        void testSyncArticleException() {
            SearchArticleVo vo = new SearchArticleVo();
            vo.setId(300L);
            vo.setTitle("异常");
            when(articleClient.getContent(300L)).thenThrow(new RuntimeException("feign down"));

            ResponseResult r = articleSearchService.syncArticle(vo);

            assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
            verify(elasticsearchOperations, never()).save(any(SearchArticle.class));
        }
    }

    // ==================== BM25 关键词召回（RAG 混合检索） ====================

    @Nested
    @DisplayName("bm25Recall - RAG 关键词召回")
    class Bm25Recall {

        @Test
        @DisplayName("入参为空 / 检索词为空 → 参数非法，且不查 ES")
        void rejectsBlankQuery() {
            ResponseResult r1 = articleSearchService.bm25Recall(null);
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r1.getCode());

            Bm25RecallDto blank = new Bm25RecallDto();
            blank.setQuery("   ");
            ResponseResult r2 = articleSearchService.bm25Recall(blank);
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r2.getCode());

            verify(elasticsearchOperations, never()).search(any(NativeQuery.class), eq(SearchArticle.class));
        }

        @Test
        @DisplayName("topK 缺省为 15")
        void defaultsTopKWhenAbsent() {
            stubSearchReturns();
            Bm25RecallDto dto = new Bm25RecallDto();
            dto.setQuery("Java");

            articleSearchService.bm25Recall(dto);

            assertEquals(15, capturedPageSize());
        }

        @Test
        @DisplayName("topK <= 0 走缺省，超上限夹到 50")
        void clampsTopK() {
            Bm25RecallDto dto = new Bm25RecallDto();
            dto.setQuery("Java");

            stubSearchReturns();
            dto.setTopK(-3);
            articleSearchService.bm25Recall(dto);
            assertEquals(15, capturedPageSize());

            reset(elasticsearchOperations);
            stubSearchReturns();
            dto.setTopK(999);
            articleSearchService.bm25Recall(dto);
            assertEquals(50, capturedPageSize());
        }

        @Test
        @DisplayName("返回 id + score，跳过无正文文档与无 id 文档")
        void returnsIdAndScore() {
            SearchHit<SearchArticle> ok = simpleHit(article(11L, "标题"), 1.5f);
            SearchHit<SearchArticle> noContent = mock(SearchHit.class);
            when(noContent.getContent()).thenReturn(null);
            SearchHit<SearchArticle> noId = mock(SearchHit.class);
            when(noId.getContent()).thenReturn(new SearchArticle());

            stubSearchReturns(ok, noContent, noId);

            Bm25RecallDto dto = new Bm25RecallDto();
            dto.setQuery("Java");
            ResponseResult r = articleSearchService.bm25Recall(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
            assertEquals(1, list.size());
            assertEquals("11", list.get(0).get("id"));
            assertEquals(Float.valueOf(1.5f), list.get(0).get("score"));
        }

        @Test
        @DisplayName("ES 异常 → 返回服务端错误（不向上抛，调用方按空召回降级）")
        void returnsServerErrorOnFailure() {
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(SearchArticle.class)))
                    .thenThrow(new RuntimeException("es down"));
            Bm25RecallDto dto = new Bm25RecallDto();
            dto.setQuery("Java");

            ResponseResult r = articleSearchService.bm25Recall(dto);

            assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
        }

        /** 取出实际下发到 ES 的分页条数（topK 落在 pageable 上） */
        private int capturedPageSize() {
            ArgumentCaptor<NativeQuery> captor = ArgumentCaptor.forClass(NativeQuery.class);
            verify(elasticsearchOperations).search(captor.capture(), eq(SearchArticle.class));
            return captor.getValue().getPageable().getPageSize();
        }
    }

    // ==================== DB↔ES 索引对账 ====================

    @Nested
    @DisplayName("missingArticleIds - 索引对账")
    class MissingArticleIds {

        @Test
        @DisplayName("候选为空 → 直接返空，不查 ES")
        void skipsEmptyCandidates() {
            assertTrue(articleSearchService.missingArticleIds(null).isEmpty());
            assertTrue(articleSearchService.missingArticleIds(List.of()).isEmpty());

            verify(elasticsearchOperations, never()).search(any(NativeQuery.class), eq(SearchArticle.class));
        }

        @Test
        @DisplayName("返回不在索引里的 id（求差集）")
        void returnsMissingIds() {
            stubSearchReturns(indexedHit("1"), indexedHit("3"));

            List<Long> missing = articleSearchService.missingArticleIds(List.of(1L, 2L, 3L, 4L));

            assertEquals(List.of(2L, 4L), missing);
        }

        @Test
        @DisplayName("全部在索引里 → 返空")
        void returnsEmptyWhenAllIndexed() {
            stubSearchReturns(indexedHit("7"));

            assertTrue(articleSearchService.missingArticleIds(List.of(7L)).isEmpty());
        }

        @Test
        @DisplayName("ES 异常必须抛出，不能降级成『没有缺失』")
        void throwsOnEsFailure() {
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(SearchArticle.class)))
                    .thenThrow(new RuntimeException("es down"));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> articleSearchService.missingArticleIds(List.of(1L)));

            assertEquals("对账查询 ES 失败", ex.getMessage());
        }

        private SearchHit<SearchArticle> indexedHit(String documentId) {
            SearchHit<SearchArticle> hit = mock(SearchHit.class);
            when(hit.getId()).thenReturn(documentId);
            return hit;
        }
    }

    // ==================== 下架时移除索引 ====================

    @Nested
    @DisplayName("removeArticleIndex - 下架移除索引文档")
    class RemoveArticleIndex {

        @Test
        @DisplayName("articleId 为空 → 参数非法")
        void rejectsNullId() {
            ResponseResult r = articleSearchService.removeArticleIndex(null);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
            verify(elasticsearchOperations, never()).delete(anyString(), eq(SearchArticle.class));
        }

        @Test
        @DisplayName("按文档 _id 删除")
        void deletesByDocumentId() {
            ResponseResult r = articleSearchService.removeArticleIndex(99L);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            verify(elasticsearchOperations).delete("99", SearchArticle.class);
        }

        @Test
        @DisplayName("ES 异常必须抛出，调用方靠它判断下架是否真的生效")
        void throwsOnEsFailure() {
            when(elasticsearchOperations.delete(anyString(), eq(SearchArticle.class)))
                    .thenThrow(new RuntimeException("es down"));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> articleSearchService.removeArticleIndex(99L));

            assertTrue(ex.getMessage().contains("99"));
        }
    }

    // ==================== 语义兜底召回 + 搜索历史 ====================

    @Nested
    @DisplayName("search - 语义兜底与搜索历史")
    class SemanticFillAndHistory {

        @Test
        @DisplayName("显式关闭 semantic → 不调向量召回")
        void skipsWhenSemanticDisabled() {
            UserSearchDto dto = dto("Java", 1, 5);
            dto.setSemantic(false);
            stubSearchReturns();

            articleSearchService.search(dto);

            verifyNoInteractions(semanticSearchClient);
        }

        @Test
        @DisplayName("非首页不兜底（补召回会打乱分页）")
        void skipsWhenNotFirstPage() {
            UserSearchDto dto = dto("Java", 2, 5);
            stubSearchReturns();

            articleSearchService.search(dto);

            verifyNoInteractions(semanticSearchClient);
        }

        @Test
        @DisplayName("首页已满一页 → 不兜底")
        void skipsWhenPageAlreadyFull() {
            UserSearchDto dto = dto("Java", 1, 1);
            stubSearchReturns(mockHit(article(1L, "命中"), null));

            articleSearchService.search(dto);

            verifyNoInteractions(semanticSearchClient);
        }

        @Test
        @DisplayName("首页命中不足 → 补足一页即停，跳过重复 id / 空 id / 非 Map 元素")
        void fillsOnePageAndDeduplicates() {
            UserSearchDto dto = dto("Java", 1, 3);
            stubSearchReturns(mockHit(article(1L, "关键词命中"), null));

            Map<String, Object> duplicate = new HashMap<>();
            duplicate.put("id", 1L);
            Map<String, Object> blankId = new HashMap<>();
            blankId.put("id", null);
            Map<String, Object> fresh = new HashMap<>();
            fresh.put("id", 2L);
            fresh.put("title", "语义召回");
            Map<String, Object> overflow = new HashMap<>();
            overflow.put("id", 3L);
            overflow.put("title", "补满后不该再进来");
            Map<String, Object> beyond = new HashMap<>();
            beyond.put("id", 4L);
            beyond.put("title", "更不该进来");
            when(semanticSearchClient.semanticSearch(any()))
                    .thenReturn(ResponseResult.okResult(
                            List.of(duplicate, blankId, fresh, overflow, beyond, "不是 Map")));

            ResponseResult r = articleSearchService.search(dto);

            List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
            // 关键词 1 条 + 语义补 2 条 = 满一页；溢出的 id=4 因 break 未被处理
            assertEquals(3, list.size());
            assertEquals("关键词命中", list.get(0).get("title"));
            assertEquals("语义召回", list.get(1).get("title"));
            assertEquals("补满后不该再进来", list.get(2).get("title"));
        }

        @Test
        @DisplayName("向量召回失败 → 吞掉异常，仍返回关键词结果")
        void swallowsSemanticFailure() {
            UserSearchDto dto = dto("Java", 1, 5);
            stubSearchReturns(mockHit(article(1L, "命中"), null));
            when(semanticSearchClient.semanticSearch(any()))
                    .thenThrow(new RuntimeException("content down"));

            ResponseResult r = articleSearchService.search(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            assertEquals(1, ((List<?>) r.getData()).size());
        }

        @Test
        @DisplayName("向量客户端未装配时跳过兜底（@Autowired(required=false)）")
        void skipsWhenClientNotWired() throws Exception {
            ArticleSearchServiceImpl serviceWithoutClient = new ArticleSearchServiceImpl();
            setField(serviceWithoutClient, "elasticsearchOperations", elasticsearchOperations);
            setField(serviceWithoutClient, "apAssociateWordsService", apAssociateWordsService);
            UserSearchDto dto = dto("Java", 1, 5);
            stubSearchReturns();

            ResponseResult r = serviceWithoutClient.search(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            verifyNoInteractions(semanticSearchClient);
        }

        @Test
        @DisplayName("登录用户才记搜索历史")
        void recordsHistoryOnlyForLoggedInUser() {
            UserSearchDto dto = dto("Java", 1, 5);
            stubSearchReturns();
            ApUser user = new ApUser();
            user.setId(42);
            AppThreadLocalUtil.setUser(user);
            try {
                articleSearchService.search(dto);

                verify(apUserSearchService).insert("Java", 42);
            } finally {
                AppThreadLocalUtil.clear();
            }
        }

        @Test
        @DisplayName("未登录不记搜索历史")
        void skipsHistoryWhenAnonymous() {
            UserSearchDto dto = dto("Java", 1, 5);
            stubSearchReturns();

            articleSearchService.search(dto);

            verify(apUserSearchService, never()).insert(anyString(), anyInt());
        }

        @Test
        @DisplayName("记历史失败不影响搜索主流程")
        void historyFailureDoesNotBreakSearch() {
            UserSearchDto dto = dto("Java", 1, 5);
            stubSearchReturns();
            doThrow(new RuntimeException("mongo down"))
                    .when(apUserSearchService).insert(anyString(), anyInt());
            ApUser user = new ApUser();
            user.setId(7);
            AppThreadLocalUtil.setUser(user);
            try {
                ResponseResult r = articleSearchService.search(dto);

                assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            } finally {
                AppThreadLocalUtil.clear();
            }
        }
    }

    // ==================== helpers ====================

    private UserSearchDto dto(String words, int pageNum, int pageSize) {
        UserSearchDto dto = new UserSearchDto();
        dto.setSearchWords(words);
        dto.setPageNum(pageNum);
        dto.setPageSize(pageSize);
        return dto;
    }

    private SearchArticle article(Long id, String title) {
        SearchArticle a = new SearchArticle();
        a.setId(id);
        a.setTitle(title);
        a.setAuthorId(id);
        a.setAuthorName("作者");
        return a;
    }

    @SuppressWarnings("unchecked")
    private SearchHit<SearchArticle> mockHit(SearchArticle article, List<String> highlightTitles) {
        SearchHit<SearchArticle> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(article);
        when(hit.getHighlightField("title")).thenReturn(highlightTitles);
        return hit;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SearchHits<SearchArticle> hits(SearchHit<SearchArticle>... items) {
        SearchHits<SearchArticle> sh = mock(SearchHits.class);
        when(sh.getSearchHits()).thenReturn(List.of(items));
        return sh;
    }

    /**
     * stub ES 检索返回给定命中。
     *
     * <p>刻意收敛成一个方法，而不是各处写 {@code when(...).thenReturn(hits(...))}：
     * {@code hits}/{@code mockHit} 内部会创建 mock 并 stubbing，若直接放在 thenReturn 的参数里，
     * Mockito 会判定为"外层 stubbing 尚未完成又开始了新的"，抛 UnfinishedStubbingException。
     */
    @SuppressWarnings("unchecked")
    private void stubSearchReturns(SearchHit<SearchArticle>... items) {
        SearchHits<SearchArticle> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of(items));
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(SearchArticle.class)))
                .thenReturn(searchHits);
    }

    /** 只 stub content + score 的 hit（用于不涉及高亮的召回场景） */
    private SearchHit<SearchArticle> simpleHit(SearchArticle article, float score) {
        SearchHit<SearchArticle> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(article);
        when(hit.getScore()).thenReturn(score);
        return hit;
    }

    /**
     * 反射注入字段：用于构造"Feign 客户端未装配"的实例。
     * semanticSearchClient 声明为 @Autowired(required = false)，真实环境可不注入，
     * 单测里需要显式复现这种形态。
     */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}