package com.heima.model.follow.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_user_follow")
public class ApFollow implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    @TableField("follow_user_id")
    private Integer followUserId;

    @TableField("created_time")
    private Date createdTime;
}