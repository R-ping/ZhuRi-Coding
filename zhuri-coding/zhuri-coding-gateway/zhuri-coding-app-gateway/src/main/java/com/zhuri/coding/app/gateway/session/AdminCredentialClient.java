package com.zhuri.coding.app.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向 user 服务校验运营账号凭据（网关侧）。
 *
 * <p><b>为什么由网关来当"登录入口"</b>：会话必须由网关创建 —— Cookie 是浏览器与网关之间的约定，
 * 下游服务再设一个 Cookie 也到不了浏览器（前端只认识网关这一个地址）。于是分工是
 * 「网关负责会话，user 服务负责凭据判断」：网关不碰 {@code ap_admin_account} 表，
 * 也不做任何口令比对，只把结果存进会话。
 *
 * <p>校验逻辑只有一份（在 user 服务），不会出现"网关认为对、服务认为错"这种最难查的分歧。
 */
@Slf4j
@Component
public class AdminCredentialClient {

    /** 内部调用凭据头，与 user 服务 {@code InternalAdminAuthController} 约定一致 */
    public static final String INTERNAL_AUTH_HEADER = "X-Internal-Auth";

    /** user 服务的注册名（Nacos），由 LoadBalancer 解析成实际实例 */
    private static final String USER_SERVICE_BASE = "http://zhuri-coding-user";

    /** 内部校验接口路径（**不带**网关服务前缀：这是服务间直连，不过网关） */
    private static final String VERIFY_PATH = "/internal/admin/verify";

    /** 与 user 服务 ResponseResult.code 保持一致的成功码 */
    private static final int CODE_SUCCESS = 200;
    /** 服务不可达/响应无法解析时的兜底码，对齐 AppHttpCodeEnum.SERVER_ERROR */
    private static final int CODE_SERVER_ERROR = 503;

    private final WebClient webClient;

    private final String internalAuthSecret;

    public AdminCredentialClient(ReactorLoadBalancerExchangeFilterFunction loadBalancerFilter,
                                 @Value("${app.internal-auth.secret:}") String internalAuthSecret) {
        this.webClient = WebClient.builder()
            .filter(loadBalancerFilter)
            .baseUrl(USER_SERVICE_BASE)
            .build();
        this.internalAuthSecret = internalAuthSecret;
    }

    /**
     * 校验登录名与口令。
     *
     * <p><b>永不抛异常</b>：任何失败都收敛成一个带业务码的结果。登录接口把异常直接抛给
     * WebFlux 的话，前端收到的是 500 空响应体，与"账号密码错"分不开。
     *
     * @param username 登录名
     * @param password 明文口令
     * @return 结果；服务不可达时 {@code ok=false} 且 code=503
     */
    public Mono<CredentialResult> verify(String username, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);

        return webClient.post()
            .uri(VERIFY_PATH)
            .header(INTERNAL_AUTH_HEADER, internalAuthSecret == null ? "" : internalAuthSecret)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(this::parse)
            .onErrorResume(e -> {
                log.error("调用用户服务校验运营凭据失败（登录将不可用）", e);
                return Mono.just(CredentialResult.unavailable());
            });
    }

    /**
     * 解析 user 服务的 {@code ResponseResult}：
     * {@code {code, message, data:{accountId, username, nickName, mustChangePassword}}}。
     */
    private CredentialResult parse(JsonNode root) {
        if (root == null) {
            return CredentialResult.unavailable();
        }
        int code = root.path("code").asInt(CODE_SERVER_ERROR);
        String message = root.path("message").asText(null);
        if (code != CODE_SUCCESS) {
            return CredentialResult.failure(code, message);
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            // code=200 但没有 data 属于协议异常，宁可当作失败也不要建出一个没有账号ID的会话
            log.error("用户服务校验接口返回成功但没有 data，按失败处理");
            return CredentialResult.unavailable();
        }
        return CredentialResult.success(
            data.path("accountId").asInt(0),
            data.path("username").asText(null),
            data.path("nickName").asText(null),
            data.path("mustChangePassword").asInt(0));
    }

    /**
     * 凭据校验结果。
     *
     * @param ok                是否通过
     * @param code              业务码（失败时透传给前端，让"密码错"与"服务挂了"能分开）
     * @param message           提示文案
     * @param accountId         运营账号ID（{@code ap_admin_account.id}）
     * @param username          登录名
     * @param nickName          展示名
     * @param mustChangePassword 1 表示仍在使用初始口令
     */
    public record CredentialResult(boolean ok, int code, String message,
                                   Integer accountId, String username, String nickName,
                                   Integer mustChangePassword) {

        static CredentialResult success(Integer accountId, String username, String nickName,
                                        Integer mustChangePassword) {
            return new CredentialResult(true, CODE_SUCCESS, "操作成功",
                accountId, username, nickName, mustChangePassword);
        }

        static CredentialResult failure(int code, String message) {
            return new CredentialResult(false, code, message == null ? "登录失败" : message,
                null, null, null, null);
        }

        static CredentialResult unavailable() {
            return new CredentialResult(false, CODE_SERVER_ERROR, "用户服务暂时不可用，请稍后重试",
                null, null, null, null);
        }
    }
}
