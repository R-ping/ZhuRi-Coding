package com.heima.model.comment.pojos;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_article_comment")
public class ApArticleComment implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 文章ID */
    private Long articleId;

    /** 评论内容 */
    private String content;

    /** 父评论ID（回复某条评论时） */
    private Long parentId;

    /** 根评论ID */
    private Long rootId;

    /** 点赞数 */
    private Integer diggCount;

    /** 回复数 */
    private Integer replyCount;

    /** 状态 0-正常 1-隐藏 */
    private Integer status;

    /** 创建时间 */
    private Date createdTime;

    /** 更新时间 */
    private Date updatedTime;

    /** 删除时间（软删除） */
    private Date deletedAt;
}