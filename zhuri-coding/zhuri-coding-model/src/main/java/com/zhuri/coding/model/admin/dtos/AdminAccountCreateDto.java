package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 新建运营账号的入参。
 *
 * <p>{@code roleCode} 可以留空：先建账号、再单独授角色是更常见的工作流
 * （尤其是"先把人录进来，等他到岗再开通权限"）。留空时账号建成但不带任何角色，
 * 此时它只能登录、登录后 {@code /admin/me} 返回"无运营权限"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminAccountCreateDto extends AdminActionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录名（1~64 字符，唯一） */
    private String username;

    /** 初始口令明文（服务端 BCrypt 后落库；**绝不回显、绝不入库明文**） */
    private String password;

    /** 展示名（可空，留空时用登录名） */
    private String nickName;

    /** 可选：建号时直接授予的角色编码 */
    private String roleCode;
}
