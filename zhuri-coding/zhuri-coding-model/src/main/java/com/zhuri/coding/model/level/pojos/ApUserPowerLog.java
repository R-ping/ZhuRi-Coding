package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

@Data
@TableName("ap_user_power_log")
public class ApUserPowerLog implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("record_date")
    private LocalDate recordDate;

    @TableField("action_score")
    private Integer actionScore;

    @TableField("influence_score")
    private Integer influenceScore;

    @TableField("quality_score")
    private Integer qualityScore;

    @TableField("violation_score")
    private Integer violationScore;

    @TableField("power_value")
    private Integer powerValue;

    @TableField("power_level")
    private Integer powerLevel;

    @TableField("created_time")
    private Date createdTime;
}