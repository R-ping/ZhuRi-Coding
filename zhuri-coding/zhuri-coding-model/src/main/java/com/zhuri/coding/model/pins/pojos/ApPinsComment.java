package com.zhuri.coding.model.pins.pojos;

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

    /** 社区治理折叠标记：1=AI 判定引战/软广等温和违规，列表默认不展示（沸点评论治理，Step3） */
    @TableField("is_hidden")
    private Integer isHidden;

    /**
     * 审核违规软删标记：1=红线违规删除（对<b>所有人</b>不可见 ——
     * 与 {@code is_hidden} 折叠"仅本人可见折叠条"的语义正交）。
     * 保留行是为了 AI 误判可被人工复核放行（复核 = 翻回 0），不再是物理删除后的不可逆。
     */
    @TableField("is_deleted")
    private Integer isDeleted;

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