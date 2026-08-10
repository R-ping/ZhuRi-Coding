package com.heima.content.service.comment;

import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.common.dtos.ResponseResult;

public interface ApCommentService {
    ResponseResult getCommentList(CommentDto dto);
    ResponseResult addComment(CommentDto dto);
    ResponseResult likeComment(CommentDto dto);
    ResponseResult getCommentById(Long id);

    /** 游标分页获取文章评论列表 */
    ResponseResult getArticleComments(Long articleId, Long cursor, Integer size);

    /** 发表文章评论 */
    ResponseResult addArticleComment(Long articleId, String content);

    /** 回复评论 */
    ResponseResult replyComment(Long commentId, String content, Long rootId);

    /** 点赞/取消点赞评论 */
    ResponseResult diggComment(Long commentId);
}