package com.zhuri.coding.model.admin.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 运营账号登录凭据（**仅用于网关↔user 服务的内部调用**，不是对外接口的入参）。
 *
 * <p>刻意的极简：只有登录名与明文口令。校验逻辑、口令比对、锁定策略全在 user 服务，
 * 网关那一侧只负责"拿凭据换会话"，不参与任何凭据判断 —— 判断逻辑散成两份，
 * 迟早出现"网关认为对、服务认为错"这种最难查的分歧。
 */
@Data
public class AdminCredentialDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录名 */
    private String username;

    /** 明文口令（只在本次调用内存在，不落日志、不落库） */
    private String password;
}
