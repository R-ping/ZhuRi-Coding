package com.zhuri.coding.content.controller.v1.article;

import com.zhuri.coding.content.service.article.ArticleRevisionService;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已发布文章修订（编辑）接口
 *
 * <p>修订内容走审核，审核期间线上继续展示旧内容；审核通过后新内容替换正文。</p>
 */
@RestController
@RequestMapping("/api/v1/article/revision")
public class ArticleRevisionController {

    @Autowired
    private ArticleRevisionService articleRevisionService;

    /** 创建或更新修订草稿 */
    @PostMapping("/save")
    public ResponseResult saveRevision(@RequestBody ApArticleDraft revision) {
        return articleRevisionService.createOrUpdateRevision(revision);
    }

    /** 提交修订审核 */
    @PostMapping("/submit")
    public ResponseResult submitRevision(@RequestBody Map<String, Long> body) {
        Long articleId = body != null ? body.get("articleId") : null;
        return articleRevisionService.submitRevision(articleId);
    }

    /** 查询待审修订 */
    @GetMapping("/pending")
    public ResponseResult getPendingRevision(@RequestParam Long articleId) {
        return articleRevisionService.getPendingRevision(articleId);
    }
}