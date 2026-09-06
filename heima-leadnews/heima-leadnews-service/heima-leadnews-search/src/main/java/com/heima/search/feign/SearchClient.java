package com.heima.search.feign;

import com.heima.apis.search.ISearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.vos.SearchArticleVo;
import com.heima.search.service.ArticleSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchClient implements ISearchClient {

    @Autowired
    private ArticleSearchService articleSearchService;
    /** 同步文章到 ES 索引 */
    @PostMapping("/api/v1/search/sync/article")
    public ResponseResult syncArticle(@RequestBody SearchArticleVo searchArticleVo){
        return articleSearchService.syncArticle(searchArticleVo);
    }

}
