package com.heima.model.pins.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_pins_comment")
public class ApPinsComment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pins_id")
    private Long pinsId;

    @TableField("user_id")
    private Integer userId;

    @TableField("user_name")
    private String userName;

    @TableField("user_avatar")
    private String userAvatar;

    @TableField("parent_id")
    private Long parentId;

    @TableField("content")
    private String content;

    /** 评论图片URL列表，逗号分隔 */
    @TableField("image_urls")
    private String imageUrls = "";

    /** 被回复用户ID（回复二级评论时使用） */
    @TableField("reply_to_user_id")
    private Integer replyToUserId;

    /** 被回复用户昵称（回复二级评论时使用） */
    @TableField("reply_to_user_name")
    private String replyToUserName = "";

    @TableField("like_count")
    private Integer likeCount;

    @TableField("reply_count")
    private Integer replyCount;

    @TableField("created_time")
    private Date createdTime;
}