package com.heima.search.service.impl;

import com.heima.apis.article.IArticleClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.UserSearchDto;
import com.heima.model.search.vos.SearchArticleVo;
import com.heima.model.search.vos.TocItem;
import com.heima.search.entity.SearchArticle;
import com.heima.search.service.ApAssociateWordsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * ArticleSearchServiceImpl 单元测试（ES 文章检索 / 同步）
 *
 * 纯 @Service，依赖经 @Autowired 由 @InjectMocks 注入：
 * - ElasticsearchOperations 检索执行（search/save/update）全部 Mock；
 * - ApAssociateWordsService 联想词计数；
 * - IArticleClient Feign 获取文章正文。
 * 覆盖三条公共链路的正常与异常分支，无需启动真实 ES/Feign。
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
}