package com.heima.content.controller.v1.comment;

import com.heima.content.service.comment.ApCommentService;
import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.comment.dtos.CommentManageDto;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/comment")
public class CommentController {

    @Autowired
    private ApCommentService apCommentService;

    @PostMapping("/list")
    public ResponseResult getCommentList(@RequestBody CommentDto dto) {
        return apCommentService.getCommentList(dto);
    }

    /**
     * 创作者评论管理：分页查询当前用户文章及其评论开关状态
     */
    @PostMapping("/manage/list")
    public ResponseResult getManageCommentList(@RequestBody CommentManageDto dto) {
        return apCommentService.getArticleCommentManageList(dto);
    }

    /**
     * 创作者评论管理：开启/关闭某篇文章的评论开关
     * body: { "articleId": 123, "status": 1|0 }
     */
    @PutMapping("/manage/status")
    public ResponseResult updateCommentStatus(@RequestBody Map<String, Object> body) {
        Object rawId = body.get("articleId");
        Object rawStatus = body.get("status");
        Long articleId = rawId instanceof Number ? ((Number) rawId).longValue() : null;
        Integer status = rawStatus instanceof Number ? ((Number) rawStatus).intValue() : null;
        return apCommentService.updateArticleCommentStatus(articleId, status);
    }

    @PostMapping
    public ResponseResult addComment(@RequestBody CommentDto dto) {
        return apCommentService.addComment(dto);
    }

    @PostMapping("/like")
    public ResponseResult likeComment(@RequestBody CommentDto dto) {
        return apCommentService.likeComment(dto);
    }

    @GetMapping("/{id}")
    public ResponseResult getCommentById(@PathVariable Long id) {
        return apCommentService.getCommentById(id);
    }
}