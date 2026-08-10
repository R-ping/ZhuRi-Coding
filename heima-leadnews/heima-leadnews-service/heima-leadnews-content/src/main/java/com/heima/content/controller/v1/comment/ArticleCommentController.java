package com.heima.content.controller.v1.comment;

import com.heima.content.service.comment.ApCommentService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 文章评论控制器
 * 提供文章评论的游标分页查询、发表、回复、点赞等功能
 * 注意：与现有的 CommentController 路由不冲突，共用 ApCommentService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/comment")
public class ArticleCommentController {

    @Autowired
    private ApCommentService apCommentService;

    /**
     * 游标分页获取文章评论列表
     *
     * @param id     文章ID
     * @param cursor 游标（上一页最后一条评论的ID，第一页不传）
     * @param size   每页数量（默认10）
     * @return { "list": [...], "cursor": long, "has_more": boolean }
     */
    @GetMapping("/article/{id}/comments")
    public ResponseResult getArticleComments(
            @PathVariable("id") Long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        log.info("获取文章评论列表, articleId={}, cursor={}, size={}", id, cursor, size);
        return apCommentService.getArticleComments(id, cursor, size);
    }

    /**
     * 发表文章评论（需登录）
     *
     * @param id   文章ID
     * @param body 请求体 { "content": "评论内容" }
     */
    @PostMapping("/article/{id}/comment")
    public ResponseResult addArticleComment(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        // 检查登录
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        String content = body != null ? body.get("content") : null;
        log.info("发表文章评论, articleId={}, content={}", id, content);
        return apCommentService.addArticleComment(id, content);
    }

    /**
     * 回复评论（需登录）
     *
     * @param commentId 被回复的评论ID
     * @param body      请求体 { "content": "回复内容", "rootId": 根评论ID }
     */
    @PostMapping("/comment/{commentId}/reply")
    public ResponseResult replyComment(
            @PathVariable("commentId") Long commentId,
            @RequestBody Map<String, Object> body) {
        // 检查登录
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        String content = body != null ? (String) body.get("content") : null;
        Long rootId = body != null && body.get("rootId") != null
                ? ((Number) body.get("rootId")).longValue()
                : null;
        log.info("回复评论, commentId={}, rootId={}, content={}", commentId, rootId, content);
        return apCommentService.replyComment(commentId, content, rootId);
    }

    /**
     * 点赞/取消点赞评论（需登录）
     *
     * @param commentId 评论ID
     * @return { "liked": true/false, "likeCount": number }
     */
    @PostMapping("/comment/{commentId}/like")
    public ResponseResult diggComment(@PathVariable("commentId") Long commentId) {
        // 检查登录
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        log.info("点赞/取消点赞评论, commentId={}", commentId);
        return apCommentService.diggComment(commentId);
    }
}