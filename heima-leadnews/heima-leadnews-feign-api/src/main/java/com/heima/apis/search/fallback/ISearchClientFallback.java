package com.heima.apis.search.fallback;

import com.heima.apis.search.ISearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.vos.SearchArticleVo;
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

}