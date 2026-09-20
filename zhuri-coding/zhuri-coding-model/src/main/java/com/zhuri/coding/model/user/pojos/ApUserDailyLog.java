package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_user_daily_log")
public class ApUserDailyLog implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("power_change")
    private Integer powerChange;

    @TableField("change_type")
    private String changeType;

    @TableField("source_id")
    private Long sourceId;

    @TableField("calculated_at")
    private Date calculatedAt;

    @TableField("created_time")
    private Date createdTime;
}