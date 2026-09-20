package com.heima.model.circle.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("club_featured_pin")
public class ClubFeaturedPin implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("circle_id")
    private Long circleId;

    @TableField("pin_id")
    private Long pinId;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_time")
    private Date createdTime;
}