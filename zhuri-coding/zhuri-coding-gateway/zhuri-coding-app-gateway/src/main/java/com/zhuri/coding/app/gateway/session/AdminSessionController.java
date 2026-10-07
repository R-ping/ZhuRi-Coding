package com.zhuri.coding.app.gateway.session;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 运营后台的会话入口：登录 / 登出 / 当前会话。
 *
 * <p>这三个接口由**网关自己**处理，不转发给任何服务 —— 会话本来就是网关这一层的事
 * （Cookie 是浏览器与网关之间的约定）。凭据判断则委托给 user 服务
 * （见 {@link AdminCredentialClient}）。
 *
 * <p><b>与 C 端登录的区别</b>：C 端返回 accToken + refToken，前端要在 444 时自己刷新并重放；
 * 这里只种一个 HttpOnly 会话 Cookie，前端不需要写任何 token 逻辑，也不存在"刷新"这个概念。
 *
 * <p><b>响应体沿用 {@code {code, message, data}} 形态</b>，与各业务服务的
 * {@code ResponseResult} 一致 —— 网关不依赖 model 模块（依赖它会把 MyBatis、servlet 等
 * 一大串东西拖进响应式应用），所以这里手搓这个结构，字段名保持一致。
 */
@Slf4j
@RestController
@RequestMapping(AdminSessionKeys.SESSION_ENDPOINT_PREFIX)
public class AdminSessionController {

    private static final int CODE_SUCCESS = 200;
    /** 与服务端一致：1 = 需要登录 */
    private static final int CODE_NEED_LOGIN = 1;
    /** 与服务端一致：500 = 缺少参数 */
    private static final int CODE_PARAM_REQUIRE = 500;

    private final AdminCredentialClient credentialClient;

    public AdminSessionController(AdminCredentialClient credentialClient) {
        this.credentialClient = credentialClient;
    }

    /**
     * 登录。
     * POST /admin-session/login  body: {"username":"...","password":"..."}
     *
     * <p>成功后种下会话 Cookie，并返回身份（不含任何 token —— 这是本方案与双 token 的分界点）。
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody(required = false) Map<String, String> body,
                                                           ServerWebExchange exchange) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Mono.just(body(CODE_PARAM_REQUIRE, "请填写登录名与口令", null));
        }

        return credentialClient.verify(username.trim(), password).flatMap(result -> {
            if (!result.ok()) {
                return Mono.just(body(result.code(), result.message(), null));
            }
            return exchange.getSession().map(session -> {
                session.getAttributes().put(AdminSessionKeys.ATTR_ACCOUNT_ID, result.accountId());
                session.getAttributes().put(AdminSessionKeys.ATTR_USERNAME, result.username());
                session.getAttributes().put(AdminSessionKeys.ATTR_NICK_NAME,
                    result.nickName() == null ? result.username() : result.nickName());
                session.getAttributes().put(AdminSessionKeys.ATTR_MUST_CHANGE_PASSWORD,
                    result.mustChangePassword() != null && result.mustChangePassword() == 1);
                session.setMaxIdleTime(AdminSessionKeys.MAX_IDLE);
                log.info("运营登录成功, accountId={}, username={}", result.accountId(), result.username());

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("accountId", result.accountId());
                data.put("username", result.username());
                data.put("nickName", result.nickName());
                data.put("mustChangePassword", result.mustChangePassword());
                return body(CODE_SUCCESS, "操作成功", data);
            });
        });
    }

    /**
     * 登出：直接销毁服务端会话。
     *
     * <p>这是会话方案相对 JWT 最直接的好处 —— 登出是真的失效，而不是"前端把 token 删了，
     * 但那个 token 在服务端眼里仍然有效，直到过期"。
     *
     * <p><b>⚠️ 必须把 {@code invalidate()} 的返回值接进响应链（{@code .thenReturn}）。</b>
     * 它返回的是「删掉 Redis 里那个会话」的冷 Mono：没人订阅，就只是把内存中的状态标成
     * EXPIRED，Redis 键原样留着，一直到 8 小时空闲 TTL 到期 —— 于是"登出"退化成
     * 「前端把 Cookie 丢了」，而会话 ID 若曾经泄露（浏览器历史、正向代理日志），在这 8 小时内依然能用。
     * 框架的收尾钩子救不了这个：它见 {@code isExpired()} 为真就只清 Cookie 直接返回，
     * 不会再调用 {@code session.save()}（见 {@code DefaultWebSessionManager#save}）。
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Map<String, Object>>> logout(ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> {
            Object accountId = session.getAttribute(AdminSessionKeys.ATTR_ACCOUNT_ID);
            return session.invalidate()
                .doOnSuccess(ignored -> log.info("运营登出, accountId={}", accountId))
                .thenReturn(body(CODE_SUCCESS, "操作成功", null));
        });
    }

    /**
     * 当前会话状态。
     * GET /admin-session/me
     *
     * <p>只回答"会话是否有效、是哪个账号"，**不回答权限** —— 权限由
     * {@code /user/api/v1/admin/me} 返回（角色数据在用户库，网关拿不到）。
     * 前端启动时先调这个快速判断要不要跳登录页，再调那个拿菜单。
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(ServerWebExchange exchange) {
        return exchange.getSession().map(session -> {
            Object accountId = session.getAttribute(AdminSessionKeys.ATTR_ACCOUNT_ID);
            if (accountId == null) {
                return body(CODE_NEED_LOGIN, "需要登录", null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("username", session.getAttribute(AdminSessionKeys.ATTR_USERNAME));
            data.put("nickName", session.getAttribute(AdminSessionKeys.ATTR_NICK_NAME));
            data.put("mustChangePassword",
                Boolean.TRUE.equals(session.getAttribute(AdminSessionKeys.ATTR_MUST_CHANGE_PASSWORD)));
            return body(CODE_SUCCESS, "操作成功", data);
        });
    }

    /** 统一构造 {@code {code, message, data}}；HTTP 状态固定 200，业务结果全在 code 里 */
    private ResponseEntity<Map<String, Object>> body(int code, String message, Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("data", data);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }
}
