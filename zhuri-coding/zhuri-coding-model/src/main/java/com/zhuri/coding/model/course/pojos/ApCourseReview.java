package com.heima.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_course_review")
public class ApCourseReview implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("course_id")
    private Long courseId;

    @TableField("reviewer_id")
    private Integer reviewerId;

    @TableField("action")
    private Integer action;

    @TableField("comment")
    private String comment;

    @TableField("created_time")
    private Date createdTime;

    public enum Action {
        APPROVE(1), REJECT(2), FEEDBACK(3);
        final int code;
        Action(int code) { this.code = code; }
        public int getCode() { return code; }
    }
}