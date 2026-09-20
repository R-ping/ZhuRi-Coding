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
@TableName("ap_course_settlement")
public class ApCourseSettlement implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("author_id")
    private Integer authorId;

    @TableField("course_id")
    private Long courseId;

    @TableField("settlement_month")
    private String settlementMonth;

    @TableField("total_sales")
    private BigDecimal totalSales;

    @TableField("platform_share")
    private BigDecimal platformShare;

    @TableField("author_share")
    private BigDecimal authorShare;

    @TableField("order_count")
    private Integer orderCount;

    @TableField("status")
    private Integer status;

    @TableField("settled_at")
    private Date settledAt;

    @TableField("created_time")
    private Date createdTime;
}