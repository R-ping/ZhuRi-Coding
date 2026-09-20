package com.heima.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_course_reading_progress")
public class ApCourseReadingProgress implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    @TableField("chapter_id")
    private Long chapterId;

    @TableField("progress")
    private Float progress;

    @TableField("last_read_at")
    private Date lastReadAt;

    @TableField("is_completed")
    private Integer isCompleted;

    @TableField("completed_at")
    private Date completedAt;
}