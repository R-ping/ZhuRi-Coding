package com.heima.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_course_invitation")
public class ApCourseInvitation implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("course_id")
    private Long courseId;

    @TableField("inviter_id")
    private Integer inviterId;

    @TableField("token")
    private String token;

    @TableField("status")
    private Integer status;

    @TableField("expire_time")
    private Date expireTime;

    @TableField("created_time")
    private Date createdTime;

    public enum Status {
        PENDING(0), ACCEPTED(1), EXPIRED(2);
        final int code;
        Status(int code) { this.code = code; }
        public int getCode() { return code; }
    }
}