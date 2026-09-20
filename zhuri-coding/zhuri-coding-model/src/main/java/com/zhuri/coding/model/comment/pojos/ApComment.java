package com.zhuri.coding.model.comment.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_comment")
public class ApComment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("article_id")
    private Long articleId;

    @TableField("user_id")
    private Integer userId;

    @TableField("user_name")
    private String userName;

    @TableField("user_avatar")
    private String userAvatar;

    @TableField("parent_id")
    private Long parentId;

    /** 根评论ID（一级评论的ID，用于层级关系） */
    @TableField("root_id")
    private Long rootId;

    @TableField("content")
    private String content;

    /** 社区治理折叠标记：1=AI 判定引战/软广等温和违规，列表默认不展示 */
    @TableField("is_hidden")
    private Integer isHidden;

    /** 评论附带图片：URL 逗号分隔，独立字段（不嵌入 content），URL 已清洗去掉签名参数 */
    @TableField("comment_pics")
    private String commentPics;

    @TableField("like_count")
    private Integer likeCount;

    @TableField("reply_count")
    private Integer replyCount;

    @TableField("created_time")
    private Date createdTime;
}