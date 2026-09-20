package com.zhuri.coding.content.controller.v1.comment;

import com.zhuri.coding.content.service.comment.ApCommentService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
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
     * 分页获取某条一级评论下的二级/更深回复（每页10条）
     *
     * @param id     文章ID（仅用于日志）
     * @param rootId 一级评论ID
     * @param cursor 游标（上一页最后一条回复的ID）
     * @param size   每页数量（默认10）
     * @return { "list": [...], "cursor": long, "has_more": boolean }
     */
    @GetMapping("/article/{id}/replies")
    public ResponseResult getCommentReplies(
            @PathVariable("id") Long id,
            @RequestParam("rootId") Long rootId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        log.info("分页获取回复列表, articleId={}, rootId={}, cursor={}, size={}", id, rootId, cursor, size);
        return apCommentService.getCommentReplies(rootId, cursor, size);
    }

    /**
     * 发表文章评论（需登录）
     *
     * @param id   文章ID
     * @param body 请求体 { "content": "评论内容", "commentPics": ["url1", "url2"] }
     */
    @PostMapping("/article/{id}/comment")
    public ResponseResult addArticleComment(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body) {
        // 检查登录
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        String content = body != null ? (String) body.get("content") : null;
        List<String> commentPics = parsePicList(body);
        log.info("发表文章评论, articleId={}, content={}, pics={}", id, content, commentPics);
        return apCommentService.addArticleComment(id, content, commentPics);
    }

    /**
     * 回复评论（需登录）
     *
     * @param commentId 被回复的评论ID
     * @param body      请求体 { "content": "回复内容", "rootId": 根评论ID, "commentPics": ["url1"] }
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
        List<String> commentPics = parsePicList(body);
        log.info("回复评论, commentId={}, rootId={}, content={}, pics={}", commentId, rootId, content, commentPics);
        return apCommentService.replyComment(commentId, content, rootId, commentPics);
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

    /** 从请求体解析 commentPics 列表（兼容数组或逗号分隔字符串） */
    @SuppressWarnings("unchecked")
    private List<String> parsePicList(Map<String, Object> body) {
        List<String> pics = new ArrayList<>();
        if (body == null || body.get("commentPics") == null) {
            return pics;
        }
        Object raw = body.get("commentPics");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o != null && !o.toString().trim().isEmpty()) {
                    pics.add(o.toString().trim());
                }
            }
        } else if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (!s.isEmpty()) {
                for (String part : s.split(",")) {
                    if (!part.trim().isEmpty()) {
                        pics.add(part.trim());
                    }
                }
            }
        }
        return pics;
    }
}