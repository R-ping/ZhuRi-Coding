package com.heima.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_browse_history")
public class ApBrowseHistory implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("article_id")
    private Long articleId;

    @TableField("target_type")
    private Integer targetType;

    @TableField("article_title")
    private String articleTitle;

    @TableField("author_id")
    private Long authorId;

    @TableField("author_name")
    private String authorName;

    @TableField("author_avatar")
    private String authorAvatar;

    @TableField("summary")
    private String summary;

    @TableField("read_count")
    private Integer readCount;

    @TableField("like_count")
    private Integer likeCount;

    @TableField("comment_count")
    private Integer commentCount;

    @TableField("browse_time")
    private Date browseTime;

    @TableField("is_deleted")
    private Boolean isDeleted;

    @TableField("deleted_at")
    private Date deletedAt;
}