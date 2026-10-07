package com.zhuri.coding.model.admin.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营账号角色绑定（ap_admin_account_role，库：leadnews_user）。
 *
 * <p>只回答"这个运营账号是什么运营"。角色编码取值见
 * {@link com.zhuri.coding.model.admin.AdminRole}；库里出现认不出的编码时按无权限处理，
 * 不报错、也不静默修正 —— 静默吃掉只会让"配错角色"更难排查。
 *
 * <p>绑定的是 {@link ApAdminAccount#getId()}，**不是** C 端账号 ID。历史上有一张
 * {@code ap_admin_user_role} 绑在 C 端账号上，已由
 * {@code db/migrations/drop_ap_admin_user_role.sql} 删除；两张表名相似、语义相反，
 * 混用会把"ID 撞上就拿到权限"这个洞重新打开。
 *
 * <p>一人可持多角色，权限取并集（见 {@code AdminRole#permissionsOf}）。
 */
@Data
@TableName("ap_admin_account_role")
public class ApAdminAccountRole implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 运营账号ID（ap_admin_account.id） */
    @TableField("account_id")
    private Integer accountId;

    /** 角色编码：AUDITOR / OPERATOR / SUPER_ADMIN */
    @TableField("role_code")
    private String roleCode;

    /** 授权人运营账号ID；初始迁移为 NULL（没有"谁授的"这回事） */
    @TableField("granted_by")
    private Integer grantedBy;

    /** 授权时间 */
    @TableField("granted_time")
    private Date grantedTime;

    /** 授权备注（为什么给他这个角色） */
    @TableField("remark")
    private String remark;
}
