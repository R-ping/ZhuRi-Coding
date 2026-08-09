package com.heima.content.feign;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.heima.apis.article.IArticleClient;
import com.heima.content.service.article.ApArticleContentService;
import com.heima.content.service.article.ApArticleEventService;
import com.heima.content.service.article.ApArticleService;
import com.heima.content.service.article.ArticleStatisticsService;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArticleClient implements IArticleClient {

    @Autowired
    private ApArticleService apArticleService;
    @Autowired
    private ApArticleContentService apArticleContentService;

    @Autowired
    private ApArticleEventService apArticleEventService;

    @Autowired
    private ArticleTaskService articleTaskService;

    @Autowired
    private ArticleStatisticsService articleStatisticsService;

    @PostMapping("/api/v1/article/event")
    public void eventUpdate(@RequestBody ArticleEvent event) {
        apArticleEventService.updateEvent(event);
    }

    @GetMapping("/api/v1/article/content")
    public ResponseResult getContent(@RequestParam("articleId") Long articleId) {
        ApArticleContent articleContent = apArticleContentService.getOne(
            new QueryWrapper<ApArticleContent>().eq("article_id", articleId));
        if (articleContent == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(articleContent.getContent());
    }

    @GetMapping("/api/v1/article/info")
    public ApArticle getArticleInfo(@RequestParam("articleId") Long articleId) {
        return apArticleService.getById(articleId);
    }

    @PostMapping("/api/v1/article/publish")
    public ResponseResult publishArticle(@RequestParam("articleId") Long articleId) {
        articleTaskService.publishArticle(articleId);
           return ResponseResult.okResult();
    }

    @PostMapping("/api/v1/article/list")
    public List<Map<String, Object>> listByAuthorId(@RequestBody ArticleDto dto) {
        return apArticleService.listByAuthorId(dto);
    }

    @GetMapping("/api/v1/article/feign/statistics")
    public ResponseResult getStatisticsFeign(@RequestParam("userId") Long userId) {
        return articleStatisticsService.getUserStatistics(userId);
    }
}
