package com.heima.search.service.impl;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.json.JsonData;
import com.heima.apis.article.IArticleClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.Bm25RecallDto;
import com.heima.model.search.dtos.UserSearchDto;
import com.heima.model.search.vos.SearchArticleVo;
import com.heima.model.user.pojos.ApUser;
import com.heima.search.entity.SearchArticle;
import com.heima.search.service.ApAssociateWordsService;
import com.heima.search.service.ApUserSearchService;
import com.heima.search.service.ArticleSearchService;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightFieldParameters;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ArticleSearchServiceImpl implements ArticleSearchService {

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private ApAssociateWordsService apAssociateWordsService;
    @Autowired
    private ApUserSearchService apUserSearchService;
    @Autowired
    private IArticleClient articleClient;

    @Autowired(required = false)
    private com.heima.apis.article.ISemanticSearchClient semanticSearchClient;

    /** 语义召回兜底：每页最多补充条数 */
    private static final int SEMANTIC_FILL_MAX = 10;

    @Value("${elasticsearch.article.index:app_info_article}")
    private String articleIndexName;

    /**
     * es文章分页检索
     */
    @Override
    public ResponseResult search(UserSearchDto dto) {
        //1.检查参数
        if (dto == null || StringUtils.isBlank(dto.getSearchWords())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        apAssociateWordsService.incrementSearchCount(dto.getSearchWords());

        // 2. 构建高亮查询
        HighlightField highlightField = new HighlightField("title",
                HighlightFieldParameters.builder().build());
        Highlight highlight = new Highlight(
                HighlightParameters.builder()
                        .withPreTags("<font style='color: red; font-size: inherit;'>")
                        .withPostTags("</font>")
                        .build(),
                List.of(highlightField));
        HighlightQuery highlightQuery = new HighlightQuery(highlight, null);

        // 3. 构建查询
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    // 关键字分词查询（title + content）
                    b.must(m -> m.queryString(qs -> qs
                            .fields("title", "content")
                            .query(dto.getSearchWords())
                            .defaultOperator(Operator.Or)
                    ));
                    // 时间范围过滤（小于 minBehotTime）
                    if (dto.getMinBehotTime() != null) {
                        co.elastic.clients.elasticsearch._types.query_dsl.DateRangeQuery dateRange =
                            new co.elastic.clients.elasticsearch._types.query_dsl.DateRangeQuery.Builder()
                                .field("publishTime")
                                .lt(String.valueOf(dto.getMinBehotTime().getTime()))
                                .build();
                        b.filter(f -> f.range(r -> r.date(dateRange)));
                    }
                    return b;
                }))
                .withPageable(PageRequest.of(
                        dto.getPageNum() > 0 ? dto.getPageNum() - 1 : 0,
                        dto.getPageSize() > 0 ? dto.getPageSize() : 10
                ))
                .withSort(Sort.by(Sort.Direction.DESC, "publishTime"))
                .withHighlightQuery(highlightQuery)
                .build();

        // 4. 执行搜索
        SearchHits<SearchArticle> searchHits = elasticsearchOperations.search(nativeQuery, SearchArticle.class);

        // 5. 记录搜索历史（仅登录用户；搜索接口匿名可访问，未登录不记录）
        recordSearchHistory(dto.getSearchWords());

        // 6. 结果封装
        List<Map<String, Object>> list = searchHits.getSearchHits().stream().map(hit -> {
            SearchArticle article = hit.getContent();
            Map<String, Object> map = new HashMap<>();
            map.put("id", article.getId() != null ? String.valueOf(article.getId()) : "");
            map.put("title", article.getTitle());
            map.put("publishTime", article.getPublishTime());
            map.put("layout", article.getLayout());
            map.put("images", article.getImages());
            map.put("authorId", article.getAuthorId() != null ? String.valueOf(article.getAuthorId()) : "");
            map.put("authorName", article.getAuthorName());
            map.put("staticUrl", article.getStaticUrl());
            map.put("content", article.getContent());
            // 处理高亮标题
            List<String> highlightTitles = hit.getHighlightField("title");
            if (highlightTitles != null && !highlightTitles.isEmpty()) {
                map.put("h_title", String.join("", highlightTitles));
            } else {
                map.put("h_title", article.getTitle());
            }
            return map;
        }).collect(Collectors.toList());

        // 7. 语义兜底召回（向量化增强）：关键词 BM25 命中不足且开启 semantic 时，
        // 调 content 向量库补召回（同义/口语改写场景"换词搜不到"），去重后补足一页
        boolean semanticEnabled = dto.getSemantic() == null || Boolean.TRUE.equals(dto.getSemantic());
        int pageNum = dto.getPageNum() > 0 ? dto.getPageNum() : 1;
        int pageSize = dto.getPageSize() > 0 ? dto.getPageSize() : 10;
        if (semanticEnabled && pageNum == 1 && list.size() < pageSize
            && semanticSearchClient != null) {
            try {
                com.heima.model.search.dtos.SemanticSearchDto sdto =
                    new com.heima.model.search.dtos.SemanticSearchDto();
                sdto.setSearchWords(dto.getSearchWords());
                sdto.setTopK(Math.min(pageSize + SEMANTIC_FILL_MAX, 20));
                ResponseResult sr = semanticSearchClient.semanticSearch(sdto);
                if (sr != null && sr.getData() instanceof List) {
                    java.util.Set<String> existIds = list.stream()
                        .map(m -> String.valueOf(m.get("id"))).collect(Collectors.toSet());
                    int filled = 0;
                    for (Object obj : (List<?>) sr.getData()) {
                        if (!(obj instanceof Map)) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> item = (Map<String, Object>) obj;
                        String id = String.valueOf(item.get("id"));
                        if (id == null || "null".equals(id) || existIds.contains(id)) {
                            continue;
                        }
                        list.add(item);
                        existIds.add(id);
                        filled++;
                        if (list.size() >= pageSize) {
                            break;
                        }
                    }
                    log.info("[SemanticSearch] 语义兜底补充 {} 条, words={}", filled, dto.getSearchWords());
                }
            } catch (Exception e) {
                log.warn("[SemanticSearch] 语义兜底失败，仅返回关键词结果, words={}",
                    dto.getSearchWords(), e);
            }
        }

        return ResponseResult.okResult(list);
    }

    /** BM25 召回候选上限（内部接口，防调用方传入过大 topK 拖垮 ES） */
    private static final int BM25_RECALL_MAX = 50;

    /**
     * BM25 关键词召回（内部接口，供 RAG 混合检索做 RRF 融合）。
     *
     * <p>刻意与面向用户的 search 区分：不加发布时间窗过滤（RAG 要的是最相关，不是最新）、
     * 不记搜索历史、不做语义兜底（否则 content → search → content 形成回环），
     * 且不指定 sort —— 保留 ES 默认的 _score 降序，因为 RRF 融合需要的是「相关度排名」。
     */
    @Override
    public ResponseResult bm25Recall(Bm25RecallDto dto) {
        if (dto == null || StringUtils.isBlank(dto.getQuery())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "query 不能为空");
        }
        int topK = dto.getTopK() == null || dto.getTopK() <= 0
            ? 15 : Math.min(dto.getTopK(), BM25_RECALL_MAX);
        try {
            NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b.must(m -> m.queryString(qs -> qs
                    .fields("title", "content")
                    .query(dto.getQuery())
                    .defaultOperator(Operator.Or)
                ))))
                .withPageable(PageRequest.of(0, topK))
                .build();
            SearchHits<SearchArticle> searchHits = elasticsearchOperations.search(nativeQuery, SearchArticle.class);
            List<Map<String, Object>> list = new ArrayList<>();
            for (SearchHit<SearchArticle> hit : searchHits.getSearchHits()) {
                SearchArticle a = hit.getContent();
                if (a == null || a.getId() == null) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                map.put("id", String.valueOf(a.getId()));
                map.put("score", hit.getScore());
                list.add(map);
            }
            log.info("[Bm25Recall] query={}, topK={}, hits={}", dto.getQuery(), topK, list.size());
            return ResponseResult.okResult(list);
        } catch (Exception e) {
            log.warn("[Bm25Recall] 关键词召回失败, query={}", dto.getQuery(), e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "BM25 召回失败");
        }
    }

    /**
     * 记录用户搜索历史（异步，失败不影响搜索主流程）
     * @param keyword 搜索关键词
     */
    private void recordSearchHistory(String keyword) {
        try {
            ApUser user = AppThreadLocalUtil.getUser();
            if (user != null && user.getId() != null) {
                apUserSearchService.insert(keyword, user.getId());
            }
        } catch (Exception e) {
            log.warn("记录搜索历史失败, keyword={}", keyword, e);
        }
    }

    @Override
    public ResponseResult syncArticle(SearchArticleVo searchArticleVo) {
        if (searchArticleVo == null || searchArticleVo.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章信息不能为空");
        }
        try {
            Long articleId = searchArticleVo.getId();

            // 获取文章内容
            ResponseResult contentResult = articleClient.getContent(articleId);
            if (contentResult != null && contentResult.getData() != null) {
                searchArticleVo.setContent(contentResult.getData().toString());
            }

            // 转换为 SearchArticle 实体
            SearchArticle article = new SearchArticle();
            article.setId(searchArticleVo.getId());
            article.setTitle(searchArticleVo.getTitle());
            article.setPublishTime(searchArticleVo.getPublishTime());
            article.setLayout(searchArticleVo.getLayout());
            article.setImages(searchArticleVo.getImages());
            article.setAuthorId(searchArticleVo.getAuthorId());
            article.setAuthorName(searchArticleVo.getAuthorName());
            article.setStaticUrl(searchArticleVo.getStaticUrl());
            article.setFileName(searchArticleVo.getFileName());
            article.setContent(searchArticleVo.getContent());
            article.setHtmlContent(searchArticleVo.getHtmlContent());
            // 转换 tocList
            if (searchArticleVo.getTocList() != null) {
                article.setTocList(searchArticleVo.getTocList().stream()
                        .map(item -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", item.getId());
                            m.put("level", item.getLevel());
                            m.put("text", item.getText());
                            return m;
                        })
                        .collect(Collectors.toList()));
            }
            article.setAuthorWorks(searchArticleVo.getAuthorWorks());
            // 发布态一步写入：调用方保证 syncArticle 时文章已置 PUBLISHED(9)，避免事后二次更新
            article.setStatus(9);

            // 索引到 ES
            elasticsearchOperations.save(article);
            log.info("文章同步到ES成功, articleId={}", articleId);
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        } catch (Exception e) {
            log.error("同步文章到ES索引失败, articleId={}", searchArticleVo.getId(), e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "同步文章到ES索引失败");
        }
    }
}