package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("user_daily_stats")
public class UserDailyStats implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("stat_date")
    private Date statDate;

    @TableField("increment_collection")
    private Integer incrementCollection;

    @TableField("increment_likes")
    private Integer incrementLikes;

    @TableField("increment_fans")
    private Integer incrementFans;

    @TableField("created_time")
    private Date createdTime;
}