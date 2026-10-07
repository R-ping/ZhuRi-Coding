package com.zhuri.coding.user.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.admin.dtos.UserBanDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.user.service.UserBanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 用户处置（警告 / 封禁 / 解封）。
 *
 * <p><b>协议说明：路径不是 {@code /api/v1/user/...}，而是 {@code /api/v1/admin/users}</b>。
 * user 服务其余接口都在 {@code /api/v1/user/**} 下，这里是刻意的例外 ——
 * 运营接口统一收在 {@code /api/v1/admin/**} 前缀下，才能用一条路径规则挂上鉴权拦截器
 * （见 {@code UserWebMvcConfig#ADMIN_PATH_PATTERNS}）。加进那个前缀就等于加进了鉴权范围，
 * 漏加就是裸奔。
 *
 * <p><b>经网关访问时前缀是 {@code /user/api/v1/admin/...}</b>（网关按服务名路由），
 * 前端别照着本地端口拼 {@code /api/v1/admin} 然后找不到服务。
 *
 * <p><b>警告为什么和封禁拆成两个权限点</b>：警告是可逆的提醒，运营就能做；
 * 封禁会让人登不进来、影响面大，只给超管（见 {@code AdminRole}）。
 * 于是"先警告、再升级为封禁"这条路径天然需要两级人，而不是一个人说了算。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserDispositionController {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private UserBanService userBanService;

    /**
     * 封禁名单。
     * GET /api/v1/admin/users/ban-list?page=1&size=20
     *
     * <p>只列"仍在封禁中"的账号；已到期的自动消失，历史去处置记录里看。
     * 返回体里带 {@code serverTime}：封禁是否到期由服务端时间判定，
     * 前端拿它算"还剩几天"，免得客户端时钟不准时显示成已解封。
     */
    @GetMapping("/ban-list")
    @RequireAdminPermission(AdminPermission.USER_BAN)
    public ResponseResult banList(@RequestParam(value = "page", defaultValue = "1") Integer page,
                                  @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return userBanService.page(page, size);
    }

    /**
     * 某账号的处置记录（警告/封禁/解封流水）。
     * GET /api/v1/admin/users/{userId}/records?page=1&size=20
     *
     * <p>权限点是 {@code USER_WARN} 而不是 {@code USER_BAN}：这个页面服务于"我警告过几次了、
     * 该不该升级"，是执行警告的人需要的信息。只给超管看会逼着运营每次去问超管。
     */
    @GetMapping("/{userId}/records")
    @RequireAdminPermission(AdminPermission.USER_WARN)
    public ResponseResult records(@PathVariable("userId") Integer userId,
                                  @RequestParam(value = "page", defaultValue = "1") Integer page,
                                  @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return userBanService.records(userId, page, size);
    }

    /**
     * 警告账号。
     * POST /api/v1/admin/users/{userId}/warn
     *
     * <p>入参直接复用 {@link AdminActionDto}：只要一个理由，没必要为它单开一个子类。
     */
    @PostMapping("/{userId}/warn")
    @RequireAdminPermission(AdminPermission.USER_WARN)
    public ResponseResult warn(@PathVariable("userId") Integer userId,
                               @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validate(userId, dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return userBanService.warn(userId, dto.getReason().trim());
    }

    /**
     * 封禁账号。
     * POST /api/v1/admin/users/{userId}/ban  body: {"reason":"...","days":7}
     *
     * <p>{@code days} 为空表示永久封禁。
     */
    @PostMapping("/{userId}/ban")
    @RequireAdminPermission(AdminPermission.USER_BAN)
    public ResponseResult ban(@PathVariable("userId") Integer userId,
                              @RequestBody UserBanDto dto) {
        ResponseResult invalid = validate(userId, dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        if (!dto.permanent() && dto.getDays() > UserBanDto.MAX_DAYS) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "封禁天数不能超过 " + UserBanDto.MAX_DAYS + " 天；更长的限制请直接使用永久封禁");
        }
        // 归一化：把"≤0 天"这类表达统一收敛成 null（永久），服务端只认"null = 永久"一种口径
        Integer days = dto.permanent() ? null : dto.getDays();
        return userBanService.ban(userId, dto.getReason().trim(), days);
    }

    /**
     * 解封账号。
     * POST /api/v1/admin/users/{userId}/unban
     */
    @PostMapping("/{userId}/unban")
    @RequireAdminPermission(AdminPermission.USER_BAN)
    public ResponseResult unban(@PathVariable("userId") Integer userId,
                                @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validate(userId, dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return userBanService.unban(userId, dto.getReason().trim());
    }

    /**
     * 三个写接口共用的入参校验，返回 null 表示通过。
     *
     * <p>理由必须非空。处置账号是重动作，当事人事后一定会问"凭什么"，
     * 而理由事后补不回来 —— 操作时不想写，事后就不会写。所以在这里挡住。
     */
    private ResponseResult validate(Integer userId, String rawReason) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "缺少账号ID");
        }
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写处置理由");
        }
        if (reason.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "处置理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
