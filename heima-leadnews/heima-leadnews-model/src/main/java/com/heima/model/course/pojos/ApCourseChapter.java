package com.heima.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_course_chapter")
public class ApCourseChapter implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("course_id")
    private Long courseId;

    @TableField("title")
    private String title;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("content")
    private String content;

    @TableField("word_count")
    private Integer wordCount;

    @TableField("is_free")
    private Byte isFree;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    @TableField("status")
    private Integer status;

    @TableField("estimated_minutes")
    private Integer estimatedMinutes;

    @TableField("comment_count")
    private Integer commentCount;

    /** 作者提交审核留言 */
    @TableField("review_note")
    private String reviewNote;
}