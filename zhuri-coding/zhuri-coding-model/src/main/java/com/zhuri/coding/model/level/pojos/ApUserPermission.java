package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_user_permission")
public class ApUserPermission implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("permission_code")
    private String permissionCode;

    @TableField("granted_at")
    private Date grantedAt;

    @TableField("expired_at")
    private Date expiredAt;

    @TableField("created_time")
    private Date createdTime;
}