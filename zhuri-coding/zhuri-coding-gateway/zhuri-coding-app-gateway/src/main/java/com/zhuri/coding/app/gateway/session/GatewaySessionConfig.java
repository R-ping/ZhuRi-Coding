package com.zhuri.coding.app.gateway.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

/**
 * 运营会话的 Cookie 策略。
 *
 * <p>会话存储本身（Redis）交给 Spring Boot 自动配置 —— 类路径上有
 * {@code spring-session-data-redis} 且是响应式应用，Boot 会装配
 * {@code ReactiveRedisSessionRepository}。这里只显式接管 Cookie 的三个属性，
 * 因为它们的默认值在本场景下都是不安全的：
 *
 * <ul>
 *   <li><b>HttpOnly</b>：默认 {@code false}。不设的话 {@code document.cookie} 能读到会话 ID，
 *       一次 XSS 就等于把会话送出去。</li>
 *   <li><b>SameSite=Strict</b>：默认不设置，浏览器按 Lax 处理；显式设 Strict 后，
 *       从任何第三方站点发起的请求都不会带上这个 Cookie —— 于是**不需要额外的 CSRF token**
 *       就挡住了跨站请求伪造。这是选定同源部署方案后能拿到的最省事的一道防线。</li>
 *   <li><b>Path=/</b>：默认是当前请求路径，会导致 {@code /admin-session/login} 建的会话
 *       在 {@code /user/...} 请求上不生效 —— 表现为"登录成功了，下一个接口还是未登录"。</li>
 * </ul>
 *
 * <p><b>⚠️ 部署前提</b>：{@code SameSite=Strict} 意味着运营前端必须与网关**同源**
 * （同 host + 同 port，通常做法是前端静态资源由网关或同机的反向代理一起提供）。
 * 若前端单独起在另一个端口，浏览器会因跨站而不发送 Cookie，登录后所有请求都是未登录 ——
 * 这不是 bug，是 SameSite 的语义。那种部署方式需要改用 HTTPS + {@code SameSite=None; Secure}。
 */
@Configuration
public class GatewaySessionConfig {

    @Bean
    public WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName(AdminSessionKeys.COOKIE_NAME);
        resolver.addCookieInitializer(builder -> builder
            .httpOnly(true)
            .sameSite("Strict")
            .path("/"));
        return resolver;
    }
}
