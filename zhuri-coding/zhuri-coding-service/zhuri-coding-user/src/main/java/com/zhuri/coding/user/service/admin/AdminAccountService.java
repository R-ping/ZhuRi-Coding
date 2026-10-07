package com.zhuri.coding.user.service.admin;

import com.zhuri.coding.model.admin.dtos.AdminAccountCreateDto;
import com.zhuri.coding.model.admin.dtos.AdminAccountStatusDto;
import com.zhuri.coding.model.admin.dtos.AdminPasswordChangeDto;
import com.zhuri.coding.model.admin.dtos.AdminRoleChangeDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营账号与角色管理（user 服务）。
 *
 * <p>涵盖三件事：**登录凭据校验**（供网关建会话时调用）、**身份自述**（{@code /admin/me}）、
 * **账号与角色的日常管理**。放在一起是因为它们共享同一套不变量 ——
 * "系统里必须始终至少有一个可用超管""不能把自己关在门外"这类约束，
 * 只有写入口与读出口在一处时才守得住。
 */
public interface AdminAccountService {

    /**
     * 校验运营账号的用户名/口令（**供网关内部调用，不对外暴露**）。
     *
     * @param username    登录名
     * @param rawPassword 明文口令
     * @return 成功时 data = {@code {accountId, username, nickName, mustChangePassword}}；失败为业务错误
     */
    ResponseResult verifyCredentials(String username, String rawPassword);

    /** 当前会话的身份与权限（{@code /admin/me}）；未登录返回 NEED_LOGIN */
    ResponseResult currentIdentity();

    /** 运营账号列表（含角色），支持按登录名/展示名模糊查 */
    ResponseResult pageAccounts(Integer page, Integer size, String keyword);

    /** 新建运营账号 */
    ResponseResult createAccount(AdminAccountCreateDto dto);

    /** 启用/停用运营账号 */
    ResponseResult updateStatus(Integer accountId, AdminAccountStatusDto dto);

    /** 授予角色 */
    ResponseResult grantRole(Integer accountId, AdminRoleChangeDto dto);

    /** 回收角色 */
    ResponseResult revokeRole(Integer accountId, AdminRoleChangeDto dto);

    /** 修改自己的口令（首次登录后换掉初始口令就靠它） */
    ResponseResult changeOwnPassword(AdminPasswordChangeDto dto);
}
