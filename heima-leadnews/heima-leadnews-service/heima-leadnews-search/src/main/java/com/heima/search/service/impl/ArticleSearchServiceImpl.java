package com.heima.search.service.impl;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.json.JsonData;
import com.heima.apis.article.IArticleClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.UserSearchDto;
import com.heima.model.search.vos.SearchArticleVo;
import com.heima.search.entity.SearchArticle;
import com.heima.search.service.ApAssociateWordsService;
import com.heima.search.service.ArticleSearchService;
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
    private IArticleClient articleClient;

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
                        b.filter(f -> f.range(r -> r
                                .field("publishTime")
                                .lt(JsonData.of(dto.getMinBehotTime().getTime()))
                        ));
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

        // 5. 结果封装
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

        return ResponseResult.okResult(list);
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
    public ResponseResult updateArticleStatus(Long articleId) {
        log.info("在es中更新文章状态, articleId={}", articleId);
        try {
            // 使用 UpdateQuery 更新 ES 文档的 status 字段
            Document doc = Document.create();
            doc.put("status", 9);  // PUBLISHED
            UpdateQuery updateQuery = UpdateQuery.builder(articleId.toString())
                    .withDocument(doc)
                    .build();
            elasticsearchOperations.update(updateQuery, elasticsearchOperations.getIndexCoordinatesFor(SearchArticle.class));
            log.info("ES文章状态更新成功, articleId={}", articleId);
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        } catch (Exception e) {
            log.error("ES文章状态更新失败, articleId={}", articleId, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "更新文章状态失败");
        }
    }
}