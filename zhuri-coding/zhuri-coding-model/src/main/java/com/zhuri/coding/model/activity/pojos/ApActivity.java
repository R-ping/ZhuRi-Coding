package com.zhuri.coding.model.activity.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_activity")
public class ApActivity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("cover_image")
    private String coverImage;

    @TableField("activity_type")
    private String activityType;

    @TableField("status")
    private String status;

    @TableField("category")
    private String category;

    @TableField("start_date")
    private Date startDate;

    @TableField("end_date")
    private Date endDate;

    @TableField("topic_id")
    private Long topicId;

    @TableField("total_participants")
    private Integer totalParticipants;

    @TableField("total_read_count")
    private Long totalReadCount;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}