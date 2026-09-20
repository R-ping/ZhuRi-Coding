package com.heima.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_behavior_likes")
public class ApBehaviorLikes implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("entry_id")
    private Long entryId;

    @TableField("user_id")
    private Integer userId;

    @TableField("type")
    private Integer type;

    @TableField("operation")
    private Integer operation;

    @TableField("created_time")
    private Date createdTime;
}