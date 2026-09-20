package com.zhuri.coding.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ap_user_level")
public class ApUserLevel implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("daily_score")
    private BigDecimal dailyScore;

    @TableField("daily_level")
    private Integer dailyLevel;

    @TableField("power_value")
    private Integer powerValue;

    @TableField("power_level")
    private Integer powerLevel;

    @TableField("daily_score_today")
    private BigDecimal dailyScoreToday;

    @TableField("power_value_today")
    private Integer powerValueToday;

    @TableField("action_score")
    private Integer actionScore;

    @TableField("influence_score")
    private Integer influenceScore;

    @TableField("quality_score")
    private Integer qualityScore;

    @TableField("violation_score")
    private Integer violationScore;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}