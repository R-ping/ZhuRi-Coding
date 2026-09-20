package com.zhuri.coding.model.pins.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@TableName("ap_pins")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApPins implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("author_id")
    private Long authorId;

    @TableField("author_name")
    private String authorName;

    @TableField("author_image")
    private String authorImage;

    @TableField("user_name")
    private String userName;

    @TableField("user_avatar")
    private String userAvatar;

    @TableField("topic_id")
    private Long topicId;

    @TableField("circle_id")
    private Long circleId;

    private String content;

    @TableField("image_urls")
    private String imageUrls;

    @TableField("topic_tags")
    private String topicTags;

    @TableField("like_count")
    private Integer likes = 0;

    @TableField("comment_count")
    private Integer comment = 0;

    @TableField("share_count")
    private Integer shareCount = 0;

    @TableField("view_count")
    private Integer views = 0;

    private Byte status;
    /** 内容诚信治理：1-疑似AI水文 */
    @TableField("is_aigc")
    private Integer isAigc;

    /** 内容诚信治理：AI水文疑似分 0-100 */
    @TableField("aigc_score")
    private Integer aigcScore;

    private String reason;

    @TableField("is_deleted")
    private Boolean isDeleted = false;

    @TableField("created_time")
    private Date createdTime;

    @TableField("publish_time")
    private Date publishTime;

    @TableField("review_time")
    private Date reviewTime;

    @TableField("link_url")
    private String linkUrl = "";

    @TableField("link_title")
    private String linkTitle = "";

    public enum Status {
        DRAFT((byte) 0),
        SUBMIT((byte) 1),
        FAIL((byte) 2),
        PUBLISHED((byte) 9);

        byte code;
        Status(byte code) { this.code = code; }
        public byte getCode() { return code; }
    }
}