package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_permission_definition")
public class ApPermissionDefinition implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("permission_code")
    private String permissionCode;

    @TableField("permission_name")
    private String permissionName;

    @TableField("description")
    private String description;

    @TableField("related_level_type")
    private Integer relatedLevelType;

    @TableField("required_level")
    private Integer requiredLevel;

    @TableField("is_active")
    private Integer isActive;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}