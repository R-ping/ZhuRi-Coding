package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("user_topic_post")
public class UserTopicPost implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableField("user_id")
    private Long userId;

    @TableField("topic_id")
    private Long topicId;

    @TableField("first_post_at")
    private Date firstPostAt;

    @TableField("last_post_at")
    private Date lastPostAt;

    @TableField("post_count")
    private Integer postCount = 1;
}