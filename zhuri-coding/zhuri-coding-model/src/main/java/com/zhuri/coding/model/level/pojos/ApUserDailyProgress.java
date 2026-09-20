package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户每日行为进度表
 */
@Data
@TableName("ap_user_daily_progress")
public class ApUserDailyProgress implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 统计日期 */
    @TableField("stat_date")
    private Date statDate;

    /** 行为编码 */
    @TableField("action_code")
    private String actionCode;

    /** 当日已完成次数 */
    @TableField("count")
    private Integer count;

    @TableField("updated_time")
    private Date updatedTime;
}
