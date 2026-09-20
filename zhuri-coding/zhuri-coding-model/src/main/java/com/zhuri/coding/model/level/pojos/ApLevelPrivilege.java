package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 等级特权表
 */
@Data
@TableName("ap_level_privilege")
public class ApLevelPrivilege implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 等级类型 1-逐日等级 2-逐力等级 */
    @TableField("level_type")
    private Integer levelType;

    /** 等级值 */
    @TableField("level_value")
    private Integer levelValue;

    /** 特权名称 */
    @TableField("privilege_name")
    private String privilegeName;

    /** 特权编码 */
    @TableField("privilege_code")
    private String privilegeCode;

    /** 图标名称（前端本地资源） */
    @TableField("icon_name")
    private String iconName;

    /** 海报图名称（前端本地资源） */
    @TableField("poster_name")
    private String posterName;

    /** 特权描述 */
    @TableField("description")
    private String description;

    /** 权益说明JSON：[{desc_title,desc_content}] */
    @TableField("desc_json")
    private String descJson;

    /** 所需逐日等级 */
    @TableField("need_jscore_level")
    private Integer needJscoreLevel;

    /** Web端跳转链接 */
    @TableField("web_jump_url")
    private String webJumpUrl;

    /** App端跳转链接 */
    @TableField("app_jump_url")
    private String appJumpUrl;

    /** 权益状态 1已解锁 0未解锁 */
    @TableField("priv_status")
    private Integer privStatus;

    /** 排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 是否启用 1是 0否 */
    @TableField("is_active")
    private Integer isActive;

    @TableField("created_time")
    private Date createdTime;
}
