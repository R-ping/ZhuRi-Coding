package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 行为项配置表（系统固定，所有用户一致）
 */
@Data
@TableName("ap_behavior_config")
public class ApBehaviorConfig implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 行为编码（唯一） */
    @TableField("action_code")
    private String actionCode;

    /** 行为名称 */
    @TableField("action_name")
    private String actionName;

    /** 分组类型：社区基础/社区活跃/社区学习/社区影响力 */
    @TableField("group_type")
    private String groupType;

    /** 分组排序：1社区基础 3社区学习 4社区影响力 5社区活跃 */
    @TableField("group_sort")
    private Integer groupSort;

    /** 单次掘友分 */
    @TableField("score")
    private BigDecimal score;

    /** 每日上限，-1表示无上限 */
    @TableField("daily_limit")
    private Integer dailyLimit;

    /** 图标名称（前端本地资源） */
    @TableField("icon_name")
    private String iconName;

    /** 按钮文案 */
    @TableField("btn_name")
    private String btnName;

    /** Web端跳转链接 */
    @TableField("web_jump_url")
    private String webJumpUrl;

    /** 行为排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 是否启用 1是 0否 */
    @TableField("is_active")
    private Integer isActive;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
