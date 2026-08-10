package com.heima.content.controller.v1.article;

import com.heima.content.service.article.ArticleDetailService;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/article")
@Slf4j
public class ArticleDetailController {

    @Autowired
    private ArticleDetailService articleDetailService;

    /**
     * 获取文章详情
     * GET /api/v1/article/detail/{id}
     */
    @GetMapping("/detail/{id}")
    public ResponseResult getArticleDetail(@PathVariable("id") Long id) {
        log.info("查询文章详情, id={}", id);
        return articleDetailService.getArticleDetail(id);
    }

    /**
     * 获取文章专栏信息
     * GET /api/v1/article/{id}/column
     */
    @GetMapping("/{id}/column")
    public ResponseResult getArticleColumn(@PathVariable("id") Long id) {
        log.info("查询文章专栏信息, id={}", id);
        return articleDetailService.getArticleColumn(id);
    }

    /**
     * 获取相关推荐（同频道文章）
     * GET /api/v1/article/{id}/related
     */
    @GetMapping("/{id}/related")
    public ResponseResult getRelatedArticles(
            @PathVariable("id") Long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "5") Integer size) {
        log.info("查询相关推荐, id={}, cursor={}, size={}", id, cursor, size);
        return articleDetailService.getRelatedArticles(id, cursor, size);
    }

    /**
     * 获取精选内容（同标签文章）
     * GET /api/v1/article/{id}/featured
     */
    @GetMapping("/{id}/featured")
    public ResponseResult getFeaturedArticles(
            @PathVariable("id") Long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "5") Integer size) {
        log.info("查询精选内容, id={}, cursor={}, size={}", id, cursor, size);
        return articleDetailService.getFeaturedArticles(id, cursor, size);
    }

    /**
     * 获取为你推荐（热点文章）
     * GET /api/v1/article/{id}/recommend
     */
    @GetMapping("/{id}/recommend")
    public ResponseResult getRecommendArticles(
            @PathVariable("id") Long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "5") Integer size) {
        log.info("查询为你推荐, id={}, cursor={}, size={}", id, cursor, size);
        return articleDetailService.getRecommendArticles(id, cursor, size);
    }
}