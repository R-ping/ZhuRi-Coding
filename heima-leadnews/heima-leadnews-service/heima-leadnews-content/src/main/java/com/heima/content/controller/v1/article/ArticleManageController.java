package com.heima.content.controller.v1.article;

import com.heima.content.service.article.ArticleManageService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/article/manage")
public class ArticleManageController {

    @Autowired
    private ArticleManageService articleManageService;

    @GetMapping("/list")
    public ResponseResult list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String title) {
        return articleManageService.list(null, page, size, status, title);
    }

    @GetMapping("/detail")
    public ResponseResult detail(@RequestParam Long id) {
        return articleManageService.getArticleById(id);
    }

    @GetMapping("/statistics")
    public ResponseResult statistics() {
        return articleManageService.statistics(null);
    }

    @PostMapping("/delete")
    public ResponseResult delete(@RequestBody Map<String, Long> body) {
        return articleManageService.deleteArticle(body.get("id"));
    }
}