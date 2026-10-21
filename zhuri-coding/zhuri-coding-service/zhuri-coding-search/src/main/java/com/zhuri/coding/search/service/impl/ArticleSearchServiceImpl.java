package com.zhuri.coding.search.service.impl;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.json.JsonData;
import com.zhuri.coding.apis.article.IArticleClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.dtos.UserSearchDto;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.search.entity.SearchArticle;
import com.zhuri.coding.search.service.ApAssociateWordsService;
import com.zhuri.coding.search.service.ApUserSearchService;
import com.zhuri.coding.search.service.ArticleSearchService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
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
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
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
    private com.zhuri.coding.apis.article.ISemanticSearchClient semanticSearchClient;

    /** 语义召回兜底：每页最多补充条数 */
    private static final int SEMANTIC_FILL_MAX = 10;

    @Value("${elasticsearch.article.index:app_info_article}")
    private String articleIndexName;

    /** 非可见态：草稿 / 审核中 / 审核失败 / 平台下架。已发布是 9 */
    private static final List<FieldValue> INVISIBLE_STATUS =
            List.of(FieldValue.of(0L), FieldValue.of(1L), FieldValue.of(2L), FieldValue.of(3L));

    /**
     * 检索侧统一可见性过滤：排除被标记删除的文档与处于非发布态的文档。
     *
     * <p><b>为什么用 must_not 而不是正向 {@code status=9}</b>：正向匹配要求文档必须带这个字段，
     * 存量文档一旦缺字段就会整批从结果里消失，还得重建索引才能恢复。must_not 的语义是
     * 「只排除明确标记为不可见的」，缺字段一律按可见处理 —— 加字段、加过滤都不必重建索引。
     *
     * <p><b>它在补谁的漏</b>：下线事件重试耗尽进死信后，索引里的文档没人再管。有了这层过滤，
     * 只要标记先送达，残留的「已下线但仍可搜」窗口就被关掉了；标记和物理删除都没送达的，
     * 由反向对账兜底。
     */
    private void applyVisibilityFilter(BoolQuery.Builder b) {
        b.mustNot(mn -> mn.term(t -> t.field("isDeleted").value(true)));
        b.mustNot(mn -> mn.terms(t -> t.field("status").terms(tv -> tv.value(INVISIBLE_STATUS))));
    }

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

        // 分页参数限幅：不加限制时 pageSize 可到任意大、pageNum 可无限深，等于开放一条打垮 ES 的入口
        int pageNum = Math.max(1, Math.min(dto.getPageNum(), 100));
        int pageSize = dto.getPageSize() <= 0 ? 10 : Math.min(dto.getPageSize(), 50);

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
                    // 用户输入必须转义：query_string 会把 + - : * ? ~ / 等解析为运算符/正则，
                    // 未转义时既可能直接抛解析异常（整个搜索 500），也可能构造出昂贵的正则/通配查询
                    b.must(m -> m.queryString(qs -> qs
                            .fields("title", "content")
                            .query(escapeQueryString(dto.getSearchWords()))
                            .defaultOperator(Operator.Or)
                            .lenient(true)
                            .analyzeWildcard(false)
                    ));
                    // 只返回可见内容（已删除 / 未发布态的文档即使还在索引里也不出现）
                    applyVisibilityFilter(b);
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
                .withPageable(PageRequest.of(pageNum - 1, pageSize))
                .withSort(Sort.by(Sort.Direction.DESC, "publishTime"))
                .withHighlightQuery(highlightQuery)
                .build();

        // 4. 执行搜索（解析失败/集群不可用统一降级，不把 500 堆栈抛给匿名搜索流量）
        SearchHits<SearchArticle> searchHits;
        try {
            searchHits = elasticsearchOperations.search(nativeQuery, SearchArticle.class);
        } catch (Exception e) {
            log.warn("[Search] 检索执行失败, words={}", dto.getSearchWords(), e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "搜索服务暂不可用，请稍后重试");
        }

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
        if (semanticEnabled && pageNum == 1 && list.size() < pageSize
            && semanticSearchClient != null) {
            try {
                com.zhuri.coding.model.search.dtos.SemanticSearchDto sdto =
                    new com.zhuri.coding.model.search.dtos.SemanticSearchDto();
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
                .withQuery(q -> q.bool(b -> {
                    b.must(m -> m.queryString(qs -> qs
                        .fields("title", "content")
                        .query(escapeQueryString(dto.getQuery()))
                        .defaultOperator(Operator.Or)
                        .lenient(true)
                        .analyzeWildcard(false)
                    ));
                    // RAG 引用的必须是仍在线的内容，可见性判据与面向用户的检索同一套
                    applyVisibilityFilter(b);
                    return b;
                }))
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
     * 转义 query_string 的语法字符。
     *
     * <p>{@code query_string} 是"带语法的查询语言"：{@code + - : * ? ~ / ( ) [] {} " \} 等
     * 会被解析成运算符、正则或通配符。用户搜索词是纯文本，直接拼进去轻则搜不到、重则解析异常
     * 让整个请求失败，还可能被构造成昂贵查询。转义后它们一律按字面量参与分词检索。
     */
    private static String escapeQueryString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replaceAll("([+\\-=&|><!(){}\\[\\]^\"~*?:\\\\/])", "\\\\$1");
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
            // 整文档覆盖写，所以墓碑必须显式清零：文章被下架后又重新上线时，
            // 文档里残留的 isDeleted=true 会让它永远搜不到（查询侧按此字段排除）
            article.setIsDeleted(false);

            // 索引到 ES
            elasticsearchOperations.save(article);
            log.info("文章同步到ES成功, articleId={}", articleId);
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        } catch (Exception e) {
            log.error("同步文章到ES索引失败, articleId={}", searchArticleVo.getId(), e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "同步文章到ES索引失败");
        }
    }

    @Override
    public List<Long> missingArticleIds(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<String> ids = candidateIds.stream().map(String::valueOf).toList();
            // 按文档 _id 查，而不是按字段查：SearchArticle.id 标了 @Id，映射到 ES 的 _id，不是可检索字段。
            // 一次 terms-ids 查询取回"已入索引"的 id，与候选集求差集即"缺失"。
            //
            // 查询带上可见性过滤，于是「文档还在、但已被标记删除」也算缺失 —— 正向对账会把它
            // 重新推一遍（整文档覆盖写，墓碑清零），这类文档正是删文档那步失败的残留。
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.filter(f -> f.ids(i -> i.values(ids)));
                        applyVisibilityFilter(b);
                        return b;
                    }))
                    .withPageable(PageRequest.of(0, ids.size()))
                    .build();
            Set<String> indexed = elasticsearchOperations.search(nativeQuery, SearchArticle.class)
                    .getSearchHits().stream()
                    .map(SearchHit::getId)
                    .collect(Collectors.toSet());
            List<Long> missing = candidateIds.stream()
                    .filter(id -> !indexed.contains(String.valueOf(id)))
                    .toList();
            if (!missing.isEmpty()) {
                log.warn("[INDEX-RECONCILE] 发现 {} / {} 篇已发布文章不在 ES 索引里",
                        missing.size(), candidateIds.size());
            }
            return missing;
        } catch (Exception e) {
            // 必须抛出：若降级成"没有缺失"，对账会把 ES 故障伪装成一切正常
            log.error("对账查询 ES 已索引文章失败, candidateCount={}", candidateIds.size(), e);
            throw new IllegalStateException("对账查询 ES 失败", e);
        }
    }

    /**
     * 列出索引里发布时间不早于给定时刻的文章 id（反向对账取数）。
     *
     * <p>正向对账能给出候选集（库里的已发布 id），反向对账给不出 —— 它要找的恰恰是
     * 「库不知道、索引还留着」的文档，只能让索引按时间窗自报清单。
     *
     * <p>查询失败必须抛出，与 {@link #missingArticleIds} 同理：返回空列表会让巡检误以为
     * 索引里一条多余的都没有。
     */
    @Override
    public List<Long> indexedArticleIds(Date since, int limit) {
        if (since == null || limit <= 0) {
            return Collections.emptyList();
        }
        try {
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b.filter(f -> f.range(r -> r.date(d -> d
                            .field("publishTime")
                            .gte(String.valueOf(since.getTime())))))))
                    .withPageable(PageRequest.of(0, limit))
                    .withSort(Sort.by(Sort.Direction.DESC, "publishTime"))
                    .build();
            List<Long> ids = new ArrayList<>();
            for (SearchHit<SearchArticle> hit : elasticsearchOperations
                    .search(nativeQuery, SearchArticle.class).getSearchHits()) {
                Long id = parseId(hit.getId());
                if (id != null) {
                    ids.add(id);
                }
            }
            log.info("[INDEX-RECONCILE] 索引内窗口文章 {} 篇", ids.size());
            return ids;
        } catch (Exception e) {
            log.error("对账查询 ES 索引内文章 id 失败, since={}", since, e);
            throw new IllegalStateException("对账查询 ES 索引内文章 id 失败", e);
        }
    }

    /** 文档 _id 是字符串，混进非数字 id 时跳过而不是让整轮对账崩掉 */
    private static Long parseId(String documentId) {
        if (documentId == null) {
            return null;
        }
        try {
            return Long.valueOf(documentId);
        } catch (NumberFormatException e) {
            log.warn("[INDEX-RECONCILE] 忽略非数字文档 id: {}", documentId);
            return null;
        }
    }

    /**
     * 从索引移除一篇文章（内容被平台下架或作者删除）。
     *
     * <p><b>先打墓碑、再物理删除</b>：墓碑是一次单字段部分更新，检索侧过滤它，
     * 所以标记成功那一刻文档就已经搜不到了；后面的 delete 只是清理存储与词频。
     * 两步分开的价值在于失败时的后果不同 —— delete 失败（事件进死信、无人再管）时，
     * 留下的是一篇已被标记、搜不到的文档，而不是一篇完好如初、还在结果里的幽灵。
     *
     * <p>墓碑失败只告警不中断：它失败通常意味着 ES 整体不可用，那样 delete 也会失败、
     * 由调用方重试；若因为别的偶发原因失败，也不该拦着物理删除这条更彻底的路。
     *
     * <p>ES 对不存在的 id 执行 delete 不报错（返回 not_found 而非异常），部分更新同样幂等，
     * 因此本方法被本地消息表重放多次是安全的。
     */
    @Override
    public ResponseResult removeArticleIndex(Long articleId) {
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "articleId 不能为空");
        }
        markInvisible(articleId);
        try {
            // 返回值是被删除文档的 id；文档本来就不存在时同样返回 id，无异常
            elasticsearchOperations.delete(String.valueOf(articleId), SearchArticle.class);
            log.info("文章已从ES索引移除, articleId={}", articleId);
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        } catch (Exception e) {
            // 必须抛出：调用方要靠它判断"下架是否真的生效"，吞掉等于让内容留在检索结果里
            log.error("从ES索引移除文章失败, articleId={}", articleId, e);
            throw new IllegalStateException("从ES索引移除文章失败, articleId=" + articleId, e);
        }
    }

    /**
     * 打可见性墓碑：把文档的 {@code isDeleted} 置 true（单字段部分更新，不动其它字段）。
     *
     * <p>文档不存在时部分更新返回 NOT_FOUND 而不抛异常，正好符合幂等要求。
     */
    private void markInvisible(Long articleId) {
        try {
            elasticsearchOperations.update(
                    UpdateQuery.builder(String.valueOf(articleId)).withDocument(tombstone()).build(),
                    IndexCoordinates.of(articleIndexName));
        } catch (Exception e) {
            log.warn("[ArticleIndex] 可见性标记写入失败，继续尝试物理删除, articleId={}", articleId, e);
        }
    }

    /** 墓碑文档：只含一个字段，避免部分更新顺带覆盖其它内容 */
    private static Document tombstone() {
        Document patch = Document.create();
        patch.put("isDeleted", true);
        return patch;
    }

    /**
     * 批量下线：一次 {@code _bulk} 打墓碑，一次 delete-by-query 物理删除。
     *
     * <p>为什么成批：清理量最多到单轮上限，逐篇调用是几十次往返；这个服务的定时任务跑在
     * 单线程调度器上，一轮拖太久会把审核补偿、退款重试这些任务一起推后。
     */
    @Override
    public ResponseResult removeArticleIndexBatch(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }
        List<String> ids = articleIds.stream().map(String::valueOf).toList();
        try {
            List<UpdateQuery> patches = ids.stream()
                    .map(id -> UpdateQuery.builder(id).withDocument(tombstone()).build())
                    .collect(Collectors.toList());
            elasticsearchOperations.bulkUpdate(patches, SearchArticle.class);
        } catch (Exception e) {
            log.warn("[ArticleIndex] 批量可见性标记失败，继续物理删除, count={}", ids.size(), e);
        }
        try {
            DeleteQuery deleteQuery = DeleteQuery.builder(
                    NativeQuery.builder().withQuery(q -> q.ids(i -> i.values(ids))).build()).build();
            elasticsearchOperations.delete(deleteQuery, SearchArticle.class);
            log.info("[ArticleIndex] 批量移除索引文档成功, count={}", ids.size());
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        } catch (Exception e) {
            // 必须抛出：调用方靠它判断"这批清理有没有生效"，吞掉等于让幽灵文档一直留着
            log.error("[ArticleIndex] 批量移除索引文档失败, count={}", ids.size(), e);
            throw new IllegalStateException("批量移除索引文档失败, count=" + ids.size(), e);
        }
    }
}