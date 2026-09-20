package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("user_onboarding_tasks")
public class UserOnboardingTask implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("task_type")
    private String taskType;
    @TableField("status")
    private Integer status;
    @TableField("condition_value")
    private Integer conditionValue;
    @TableField("reward_ore")
    private Integer rewardOre;
    @TableField("complete_time")
    private Date completeTime;
    @TableField("created_time")
    private Date createdTime;
}