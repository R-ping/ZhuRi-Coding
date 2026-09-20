package com.heima.content.service.comment;

import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.comment.dtos.CommentManageDto;
import com.heima.model.common.dtos.ResponseResult;

import java.util.List;

public interface ApCommentService {
    ResponseResult getCommentList(CommentDto dto);
    ResponseResult addComment(CommentDto dto);
    ResponseResult likeComment(CommentDto dto);
    ResponseResult getCommentById(Long id);

    /** 游标分页获取文章评论列表 */
    ResponseResult getArticleComments(Long articleId, Long cursor, Integer size);

    /** 发表文章评论（含附带图片，图片独立存储并清洗URL） */
    ResponseResult addArticleComment(Long articleId, String content, List<String> commentPics);

    /** 回复评论（含附带图片，图片独立存储并清洗URL） */
    ResponseResult replyComment(Long commentId, String content, Long rootId, List<String> commentPics);

    /** 分页获取某条一级评论下的二级回复（每页10条，游标分页） */
    ResponseResult getCommentReplies(Long rootId, Long cursor, Integer size);

    /** 点赞/取消点赞评论 */
    ResponseResult diggComment(Long commentId);

    /** 获取文章的一级评论数（parent_id IS NULL），用于展示与评论列表一致的评论数量 */
    long countTopComments(Long articleId);

    /** 创作者评论管理：分页查询当前用户文章及其评论开关状态 */
    ResponseResult getArticleCommentManageList(CommentManageDto dto);

    /** 创作者评论管理：开启/关闭某篇文章的评论开关（仅文章作者可操作） */
    ResponseResult updateArticleCommentStatus(Long articleId, Integer status);
}