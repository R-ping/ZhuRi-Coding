package com.zhuri.coding.app.gateway.filter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关入口级分布式限流（IP 维度，固定窗口）。
 * <p>
 * 使用 Redis 原子 Lua（INCR + 首次 EXPIRE）计数，窗口为 1 分钟。
 * 登录/注册/刷新等敏感接口采用更严格的阈值，其余接口用宽松阈值。
 * 限流在鉴权（AuthorizeFilter, order=0）之前执行（order=-1），
 * 避免未认证的刷量请求进入下游业务；/actuator 健康检查不参与限流。
 */
@Slf4j
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private static final String RATE_LIMIT_SCRIPT =
            "local c = redis.call('INCR', KEYS[1]);" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end;" +
            "return c";

    private static final DateTimeFormatter WINDOW_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 登录等敏感路径子串：命中走严格阈值 */
    private static final List<String> SENSITIVE_PATH_KEYWORDS = Arrays.asList(
            "/login", "/login_auth", "/oauth2", "/token/refresh", "/token/logout", "/social_bind", "/code");

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript = new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class);

    /** 普通接口：每 IP 每分钟最大请求数 */
    @Value("${gateway.rate-limit.ip-per-minute:600}")
    private long ipLimitPerMinute;

    /** 登录等敏感接口：每 IP 每分钟最大请求数 */
    @Value("${gateway.rate-limit.sensitive-per-minute:60}")
    private long sensitiveLimitPerMinute;

    public GatewayRateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        // 健康检查不参与限流，避免监控误报
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String ip = resolveClientIp(request);
        boolean sensitive = SENSITIVE_PATH_KEYWORDS.stream().anyMatch(path::contains);
        long limit = sensitive ? sensitiveLimitPerMinute : ipLimitPerMinute;
        String key = "gateway:ratelimit:" + (sensitive ? "sensitive:" : "common:")
                + ip + ":" + LocalDateTime.now().format(WINDOW_FORMATTER);

        return redisTemplate.execute(rateLimitScript, List.of(key), Collections.singletonList(String.valueOf(60)))
                .next() // Flux<Long> → Mono<Long>，与 GlobalFilter 返回类型对齐
                .flatMap(count -> {
                    if (count != null && count > limit) {
                        log.warn("网关限流触发: ip={}, path={}, sensitive={}, count={}, limit={}",
                                ip, path, sensitive, count, limit);
                        return reject(exchange);
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    // Redis 故障时放行（fail-open），避免限流组件拖垮整条链路
                    log.error("网关限流 Redis 异常, 放行请求, path={}", path, e);
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}".getBytes();
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 解析客户端 IP。
     *
     * <p><b>安全约定</b>：X-Forwarded-For 可被客户端自行伪造，无条件采信等于 IP 维度限流形同虚设
     * （换一个伪造值就是一个新限流桶）。故仅在直连对端为我方受信代理（回环/内网）时才采信代理头，
     * 并从右往左取第一个非受信地址（我方代理把观测到的对端追加在最右，最左可能是伪造值）。
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String remoteAddr = request.getRemoteAddress() != null
            ? request.getRemoteAddress().getAddress().getHostAddress() : null;
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = parts[i].trim();
                    if (!candidate.isEmpty() && !isTrustedProxy(candidate)) {
                        return candidate;
                    }
                }
            }
            String realIp = request.getHeaders().getFirst("X-Real-IP");
            if (StringUtils.hasText(realIp)) {
                return realIp.trim();
            }
        }
        return remoteAddr != null && !remoteAddr.isEmpty() ? remoteAddr : "unknown";
    }

    /** 是否为受信代理地址（回环 / 内网 / 链路本地），需与部署拓扑保持一致 */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            int second = secondOctet(ip);
            return second >= 16 && second <= 31;
        }
        return false;
    }

    /** 取 IPv4 第二段（判断 172.16.0.0/12 私有网段），非法格式返回 -1 */
    private static int secondOctet(String ip) {
        try {
            String[] parts = ip.split("\\.");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
