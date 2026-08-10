package com.heima.model.topic.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_topic")
public class ApTopic implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description = "";

    @TableField("cover_image")
    private String coverImage = "";

    @TableField("type")
    private Integer type = 1;

    @TableField("view_count")
    private Long viewCount = 0L;

    @TableField("participant_count")
    private Long participantCount = 0L;

    @TableField("post_count")
    private Integer postCount = 0;

    @TableField("is_recommend")
    private Integer isRecommend = 0;

    @TableField("recommend_sort")
    private Integer recommendSort = 0;

    @TableField("badge")
    private String badge = "";

    @TableField("theme_type")
    private Integer themeType = 1;

    @TableField("status")
    private Integer status;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_at")
    private Date updatedAt;
}