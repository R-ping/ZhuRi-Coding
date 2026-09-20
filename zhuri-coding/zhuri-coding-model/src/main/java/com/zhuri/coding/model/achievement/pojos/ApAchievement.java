package com.heima.model.achievement.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 成就勋章定义表（静态勋章，等级徽章不落库）
 */
@Data
@TableName("ap_achievement")
public class ApAchievement implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 勋章唯一编码 */
    @TableField("code")
    private String code;

    /** 勋章名称 */
    @TableField("name")
    private String name;

    /** 分类：1=新人成长，2=活跃成就 */
    @TableField("category")
    private Integer category;

    /** 图标（emoji 字符） */
    @TableField("icon")
    private String icon;

    /** 解锁条件文案 */
    @TableField("description")
    private String description;

    /** 触发类型：publish_article/publish_content/checkin_streak/likes/followers */
    @TableField("trigger_type")
    private String triggerType;

    /** 解锁阈值 */
    @TableField("threshold")
    private Integer threshold;

    /** 展示排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 是否启用：1=启用 0=禁用 */
    @TableField("is_active")
    private Boolean isActive;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
