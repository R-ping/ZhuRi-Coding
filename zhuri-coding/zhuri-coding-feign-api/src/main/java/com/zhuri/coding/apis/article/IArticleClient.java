package com.zhuri.coding.apis.article;

import com.zhuri.coding.apis.article.fallback.IArticleClientFallback;
import com.zhuri.coding.model.article.dtos.ArticleDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "zhuri-coding-content", contextId = "zhuri-coding-content-articleClient", fallback = IArticleClientFallback.class)
public interface IArticleClient {

    @PostMapping("/api/v1/article/event")
    public void eventUpdate(@RequestBody ArticleEvent event);

    @GetMapping("/api/v1/article/content")
    public ResponseResult getContent(@RequestParam("articleId") Long articleId);

    @GetMapping("/api/v1/article/info")
    public ApArticle getArticleInfo(@RequestParam("articleId") Long articleId);
    @PostMapping("/api/v1/article/publish")
    public ResponseResult publishArticle(@RequestParam("articleId") Long articleId);

    @PostMapping("/api/v1/article/list")
    public List<Map<String, Object>> listByAuthorId(@RequestBody ArticleDto dto);

    @GetMapping("/api/v1/article/feign/statistics")
    public ResponseResult getStatisticsFeign(@RequestParam("userId") Long userId);
}
