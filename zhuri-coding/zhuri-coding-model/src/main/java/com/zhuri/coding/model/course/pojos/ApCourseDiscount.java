package com.heima.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ap_course_discount")
public class ApCourseDiscount implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("course_id")
    private Long courseId;

    @TableField("code")
    private String code;

    @TableField("discount_type")
    private Integer discountType;

    @TableField("discount_value")
    private BigDecimal discountValue;

    @TableField("max_uses")
    private Integer maxUses;

    @TableField("used_count")
    private Integer usedCount;

    @TableField("start_time")
    private Date startTime;

    @TableField("end_time")
    private Date endTime;

    @TableField("status")
    private Integer status;

    @TableField("created_time")
    private Date createdTime;

    public enum DiscountType {
        FIXED(1), PERCENTAGE(2);
        final int code;
        DiscountType(int code) { this.code = code; }
        public int getCode() { return code; }
    }
}