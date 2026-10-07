package com.zhuri.coding.user.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminAccountCreateDto;
import com.zhuri.coding.model.admin.dtos.AdminAccountStatusDto;
import com.zhuri.coding.model.admin.dtos.AdminPasswordChangeDto;
import com.zhuri.coding.model.admin.dtos.AdminRoleChangeDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.user.service.admin.AdminAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 身份自述与运营账号/角色管理。
 *
 * <p><b>为什么身份自述（{@code /admin/me}）和账号管理放在同一个控制器</b>：
 * 它们回答的是同一个问题的两面 —— "我是谁"与"谁能是谁"。合成一个控制器，
 * 免权限点的那两个接口（{@code /admin/me} 及其子路径）与需要权限的接口在同一个
 * {@code @RequestMapping} 前缀下，路径边界一眼可见；拆成两个控制器反而要靠
 * 逐条比对路径才能确认免鉴权范围。
 *
 * <p><b>免权限点的两个接口</b>（见 {@code AdminAuthInterceptor#PERMISSION_EXEMPT_PATHS}）：
 * {@code GET /api/v1/admin/me}、{@code POST /api/v1/admin/me/password}。
 * 但它们**仍然需要登录** —— 免的是权限点，不是认证。
 *
 * <p>经网关访问时前缀是 {@code /user/api/v1/admin/...}（网关按服务名路由）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAccountController {

    @Autowired
    private AdminAccountService adminAccountService;

    /**
     * 我是谁、我有哪些权限。
     * GET /api/v1/admin/me
     *
     * <p>前端启动时调一次决定菜单。刻意不挂权限点：没开通任何角色的账号也要能问出
     * "我确实没有权限"，而不是收到一个和"系统故障"长得一样的 403。
     */
    @GetMapping("/me")
    public ResponseResult me() {
        return adminAccountService.currentIdentity();
    }

    /**
     * 修改自己的口令。
     * POST /api/v1/admin/me/password  body: {"oldPassword":"...","newPassword":"..."}
     *
     * <p>同样不挂权限点：否则用初始口令登录的人永远换不掉初始口令。
     */
    @PostMapping("/me/password")
    public ResponseResult changeOwnPassword(@RequestBody AdminPasswordChangeDto dto) {
        return adminAccountService.changeOwnPassword(dto);
    }

    /**
     * 运营账号列表（含各自角色）。
     * GET /api/v1/admin/accounts?page=1&size=20&keyword=
     *
     * <p>权限点用 {@code ROLE_GRANT} 而不是 {@code ACCOUNT_MANAGE}：这个列表的主要用途是
     * "找到人要给他授权"，是执行授权的人需要的信息。若要求 {@code ACCOUNT_MANAGE}，
     * 一个只能改角色的人就看不到任何可供授权的对象。
     */
    @GetMapping("/accounts")
    @RequireAdminPermission(AdminPermission.ROLE_GRANT)
    public ResponseResult accounts(@RequestParam(value = "page", defaultValue = "1") Integer page,
                                   @RequestParam(value = "size", defaultValue = "20") Integer size,
                                   @RequestParam(value = "keyword", required = false) String keyword) {
        return adminAccountService.pageAccounts(page, size, keyword);
    }

    /**
     * 新建运营账号。
     * POST /api/v1/admin/accounts
     * body: {"username":"...","password":"...","nickName":"...","roleCode":"...","reason":"..."}
     */
    @PostMapping("/accounts")
    @RequireAdminPermission(AdminPermission.ACCOUNT_MANAGE)
    public ResponseResult createAccount(@RequestBody AdminAccountCreateDto dto) {
        return adminAccountService.createAccount(dto);
    }

    /**
     * 启用/停用运营账号。
     * PUT /api/v1/admin/accounts/{accountId}/status  body: {"status":0,"reason":"..."}
     *
     * <p>停用会立刻踢掉该账号已签发的会话（服务端会话可以直接失效，这是选它的主要理由之一）。
     */
    @PutMapping("/accounts/{accountId}/status")
    @RequireAdminPermission(AdminPermission.ACCOUNT_MANAGE)
    public ResponseResult updateStatus(@PathVariable("accountId") Integer accountId,
                                       @RequestBody AdminAccountStatusDto dto) {
        return adminAccountService.updateStatus(accountId, dto);
    }

    /**
     * 授予角色。
     * POST /api/v1/admin/accounts/{accountId}/roles/grant  body: {"roleCode":"OPERATOR","reason":"..."}
     *
     * <p>一人可持多角色，权限取并集；重复授予会被明确拒绝而不是静默成功。
     */
    @PostMapping("/accounts/{accountId}/roles/grant")
    @RequireAdminPermission(AdminPermission.ROLE_GRANT)
    public ResponseResult grantRole(@PathVariable("accountId") Integer accountId,
                                    @RequestBody AdminRoleChangeDto dto) {
        return adminAccountService.grantRole(accountId, dto);
    }

    /**
     * 回收角色。
     * POST /api/v1/admin/accounts/{accountId}/roles/revoke  body: {"roleCode":"OPERATOR","reason":"..."}
     *
     * <p>回收立即生效（角色是每个请求现查库解析的，没有缓存窗口）；
     * 最后一个超管角色不能回收，否则角色管理会永久锁死。
     */
    @PostMapping("/accounts/{accountId}/roles/revoke")
    @RequireAdminPermission(AdminPermission.ROLE_GRANT)
    public ResponseResult revokeRole(@PathVariable("accountId") Integer accountId,
                                     @RequestBody AdminRoleChangeDto dto) {
        return adminAccountService.revokeRole(accountId, dto);
    }
}
