package com.zhuri.coding.model.admin.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营账号（ap_admin_account，库：leadnews_user）。
 *
 * <p><b>与 C 端账号完全隔离</b>：不复用 {@code ap_user}，也不共享 ID 空间。
 * 原因是运营后台是内部系统，用服务端会话鉴权比 C 端那套「accToken + refToken + 444 刷新」
 * 合适得多；而一旦两套登录并存，共用一个 ID 空间就会产生「某 C 端用户的 id 恰好等于
 * 某个有角色的运营账号 id，于是凭空获得运营权限」这条越权路径。分开建表从根上消除它。
 *
 * <p><b>口令用 BCrypt</b>（{@code spring-security-crypto}，strength=10，与 C 端 {@code ap_user.password}
 * 同一实现）。因此本类的 {@code password} 字段**绝不能**出现在任何返回给前端的 VO 里 ——
 * 需要展示账号信息时用专门的 VO，不要图省事直接序列化本类。
 */
@Data
@TableName("ap_admin_account")
public class ApAdminAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 0;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 登录名（唯一） */
    @TableField("username")
    private String username;

    /** 口令哈希（BCrypt，$2a$10$ 开头共 60 字符）—— 不对外输出 */
    @TableField("password")
    private String password;

    /** 展示名（会随会话下发给前端当昵称） */
    @TableField("nick_name")
    private String nickName;

    /** 1 启用 / 0 停用 */
    @TableField("status")
    private Integer status;

    /** 1 表示仍在使用初始口令，前端据此强提示改密 */
    @TableField("must_change_password")
    private Integer mustChangePassword;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    /** 最近一次登录成功时间（登录失败不更新，避免被用来探测账号是否存在） */
    @TableField("last_login_time")
    private Date lastLoginTime;

    @TableField("remark")
    private String remark;

    /** 是否启用；null 视为停用（fail-closed，缺字段不该等价于放行） */
    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    /** 是否仍在使用初始口令 */
    public boolean needsPasswordChange() {
        return mustChangePassword != null && mustChangePassword == 1;
    }
}
