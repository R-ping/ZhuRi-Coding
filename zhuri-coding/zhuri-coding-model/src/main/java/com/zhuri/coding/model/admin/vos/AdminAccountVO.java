package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 运营账号列表项。
 *
 * <p><b>没有 password 字段，这是刻意的</b>：账号列表是运营后台最常打开的页面，
 * 一旦把实体直接序列化出去，口令哈希就会跟着进浏览器（哈希本身不能直接登录，
 * 但可以把离线爆破的成本从"猜口令"降到"跑字典"，没有任何理由送出去）。
 * 所以展示用专门的 VO，而不是给实体加 {@code @JsonIgnore} —— 后者只在"有人记得加注解"时成立。
 */
@Data
public class AdminAccountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;

    private String username;

    private String nickName;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 1 表示仍在使用初始口令（列表里标红提示超管去催） */
    private Integer mustChangePassword;

    /** 角色编码列表；空列表表示"已建号但还没开通任何权限" */
    private List<String> roleCodes;

    private Date lastLoginTime;

    private Date createdTime;

    private String remark;
}
