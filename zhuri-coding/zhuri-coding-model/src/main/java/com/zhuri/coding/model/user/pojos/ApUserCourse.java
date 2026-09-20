package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ap_user_course")
public class ApUserCourse implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    @TableField("course_id")
    private Long courseId;

    @TableField("order_id")
    private Long orderId;

    @TableField("purchased_at")
    private Date purchasedAt;

    @TableField("is_active")
    private Byte isActive;

    @TableField("created_time")
    private Date createdTime;

    @TableField("access_type")
    private Integer accessType;

    @TableField("borrow_expire_at")
    private Date borrowExpireAt;

    @TableField("progress")
    private BigDecimal progress;

    @TableField("last_learn_chapter_id")
    private Long lastLearnChapterId;

    @TableField("last_learn_at")
    private Date lastLearnAt;

    @TableField("is_trial")
    private Integer isTrial;
}