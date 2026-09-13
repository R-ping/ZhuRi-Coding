package com.heima.apis.search.fallback;

import com.heima.apis.search.ISearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.Bm25RecallDto;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ISearchClientFallback implements ISearchClient {

    @Override
    public ResponseResult syncArticle(SearchArticleVo searchArticleVo) {
        Long articleId = searchArticleVo != null ? searchArticleVo.getId() : null;
        log.error("远程同步文章到ES索引异常, articleId={}", articleId);
        throw new RuntimeException("同步文章到ES索引异常, articleId=" + articleId);
    }

    /**
     * 关键词召回降级：返回空列表而非抛异常 —— 调用方（RAG 混合检索）据此自动退化为纯向量召回，
     * 保证 search 服务不可用时问答链路不受影响。
     */
    @Override
    public ResponseResult bm25Recall(Bm25RecallDto dto) {
        log.warn("[Bm25Recall] search 服务不可用，关键词召回降级为空（本次问答退化为纯向量召回）");
        return ResponseResult.okResult(new ArrayList<>());
    }
}