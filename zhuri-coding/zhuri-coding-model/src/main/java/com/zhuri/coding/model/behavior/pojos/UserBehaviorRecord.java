package com.heima.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户行为记录表
 */
@Data
@TableName("user_behavior_record")
public class UserBehaviorRecord implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 行为类型 */
    @TableField("behavior_type")
    private String behaviorType;

    /** 目标类型: 1-文章, 2-沸点, 3-用户, 4-课程, 5-专栏 */
    @TableField("target_type")
    private Integer targetType;

    /** 目标ID */
    @TableField("target_id")
    private Long targetId;

    /** 目标作者/被关注用户ID */
    @TableField("target_user_id")
    private Integer targetUserId;

    /** 状态: 1-有效, 0-已撤销 */
    @TableField("status")
    private Integer status;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}