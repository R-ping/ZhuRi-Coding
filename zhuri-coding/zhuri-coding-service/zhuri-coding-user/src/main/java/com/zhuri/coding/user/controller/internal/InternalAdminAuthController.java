package com.zhuri.coding.user.controller.internal;

import com.zhuri.coding.model.admin.dtos.AdminCredentialDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.user.service.admin.AdminAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 运营账号凭据校验（**内部接口，网关专用**）。
 *
 * <p>路径刻意**不在** {@code /api/v1/admin/**} 下：那个前缀是"网关转发的运营接口"，
 * 而这是"网关自己来问的服务间调用"。两者混在一起，将来给运营前缀加鉴权规则时
 * 会顺手把内部调用也挡住，表现为"登录接口 403"，排查方向完全被带偏。
 *
 * <p><b>两道防线，因为这是"拿用户名口令换账号 ID"的接口</b>：
 * <ol>
 *   <li>网关把任何一个含 {@code /internal/} 的路径直接拒绝。
 *       ⚠️ 光把接口叫"内部"是不够的：它仍然落在网关的 {@code /user/**} 路由下，
 *       {@code /user/internal/admin/verify} 经 {@code StripPrefix=1} 之后正好映射到本控制器，
 *       也就**外部可达**。所以网关侧必须显式拦一道（见 {@code AuthorizeFilter}）。</li>
 *   <li>要求共享密钥请求头 {@code X-Internal-Auth}，与网关的 {@code app.internal-auth.secret}
 *       一致才受理。密钥未配置时一律拒绝（fail-closed）—— 配置缺失绝不等于"不用校验"。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/internal/admin")
public class InternalAdminAuthController {

    /** 内部调用凭据头；与网关 {@code AdminSessionController} 写出的头一致 */
    public static final String INTERNAL_AUTH_HEADER = "X-Internal-Auth";

    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    @Autowired
    private AdminAccountService adminAccountService;

    /**
     * 校验运营账号凭据。
     * POST /internal/admin/verify  body: {"username":"...","password":"..."}
     *
     * <p>成功时 data = {@code {accountId, username, nickName, mustChangePassword}}。
     */
    @PostMapping("/verify")
    public ResponseResult verify(@RequestHeader(value = INTERNAL_AUTH_HEADER, required = false) String secret,
                                 @RequestBody(required = false) AdminCredentialDto dto) {
        if (!secretMatches(secret)) {
            // 不记录传入的密钥值：它可能是用户口令之类的东西被误填进来，日志里留原文是二次泄露
            log.warn("内部凭据校验接口收到未经授权的调用，已拒绝");
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "内部调用未授权");
        }
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }
        return adminAccountService.verifyCredentials(dto.getUsername(), dto.getPassword());
    }

    /**
     * 恒定时间比较，避免用 {@code equals} 泄漏"前几位对了"这种时序信息。
     * 密钥未配置时直接返回 false（fail-closed）。
     */
    private boolean secretMatches(String provided) {
        if (internalAuthSecret == null || internalAuthSecret.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
            provided.getBytes(StandardCharsets.UTF_8),
            internalAuthSecret.getBytes(StandardCharsets.UTF_8));
    }
}
