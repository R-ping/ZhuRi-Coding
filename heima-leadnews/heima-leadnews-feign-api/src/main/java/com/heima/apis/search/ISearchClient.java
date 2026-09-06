package com.heima.apis.search;

import com.heima.apis.search.fallback.ISearchClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.vos.SearchArticleVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "leadnews-search", contextId = "leadnews-search-searchClient", fallback = ISearchClientFallback.class)
public interface ISearchClient {

    /** 同步文章到 ES 索引 */
    @PostMapping("/api/v1/search/sync/article")
    ResponseResult syncArticle(@RequestBody SearchArticleVo searchArticleVo);
}