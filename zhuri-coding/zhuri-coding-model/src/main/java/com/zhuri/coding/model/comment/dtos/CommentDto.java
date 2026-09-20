package com.zhuri.coding.model.comment.dtos;

import java.util.List;
import lombok.Data;

@Data
public class CommentDto {
    private Long articleId;
    private Long parentId;
    private Long commentId;
    private Long replyToUserId;
    private String replyToUserName;
    private String content;
    /** 评论附带图片 URL 列表（独立字段，不嵌入 content） */
    private List<String> commentPics;
    private Integer page;
    private Integer size;
    /** 目标类型: 1-文章, 2-沸点 */
    private Integer articleType;
    /** 目标作者ID */
    private Integer targetUserId;
}