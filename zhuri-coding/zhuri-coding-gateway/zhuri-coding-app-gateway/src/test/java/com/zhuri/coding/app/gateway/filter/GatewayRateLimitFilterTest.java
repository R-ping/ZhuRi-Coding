package com.zhuri.coding.app.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GatewayRateLimitFilter 单元测试（网关入口 IP 限流）。
 *
 * <p>这个类承担两件容易被忽略的事，都用测试钉住：
 * <ol>
 *   <li><b>限流桶的选择</b>——登录等敏感路径走单独的低阈值桶，别把登录爆破和普通翻页算进同一个配额；</li>
 *   <li><b>客户端 IP 的可信来源</b>——X-Forwarded-For 客户端可以随便填，无条件采信等于
 *       "换个假 IP 就是一个新限流桶"，限流形同虚设。只有直连对端是我方受信代理时才采信代理头，
 *       且从右往左取第一个非受信地址。</li>
 * </ol>
 *
 * <p>Redis 全程 mock，不依赖真实 Redis；计数直接给定，因此不涉及时间窗口。
 * 用 key 的内容来断言"用了哪个桶 / 解析出的 IP 是谁"，比反射调用私有方法更贴近真实行为。
 */
@DisplayName("GatewayRateLimitFilter 网关入口限流")
class GatewayRateLimitFilterTest {

    private static final String COMMON_PREFIX = "gateway:ratelimit:common:";
    private static final String SENSITIVE_PREFIX = "gateway:ratelimit:sensitive:";

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);

    private final GatewayRateLimitFilter filter = new GatewayRateLimitFilter(redisTemplate);

    /** 阈值来自 @Value，单测没有容器注入，直接反射给上；不给则默认为 0，任何请求都会被判超限 */
    @BeforeEach
    void injectLimits() throws Exception {
        setField(filter, "ipLimitPerMinute", 600L);
        setField(filter, "sensitiveLimitPerMinute", 60L);
    }

    // ==================== 主流程 ====================

    @Test
    @DisplayName("健康检查不参与限流，也不碰 Redis")
    void actuatorIsExempt() {
        ServerWebExchange exchange = exchange("/actuator/health", "203.0.113.9", null, null);
        GatewayFilterChain chain = stubChain(exchange);

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("未超阈值 → 放行")
    void allowsUnderLimit() {
        ServerWebExchange exchange = exchange("/content/api/v1/article/list", "203.0.113.9", null, null);
        GatewayFilterChain chain = stubChain(exchange);
        stubRedisCount(1L);

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        assertTrue(capturedKey().startsWith(COMMON_PREFIX));
    }

    @Test
    @DisplayName("超过阈值 → HTTP 200 + 业务码 8001，且不进下游")
    void rejectsWhenOverLimit() {
        ServerWebExchange exchange = exchange("/content/api/v1/article/list", "203.0.113.9", null, null);
        ServerHttpResponse response = stubJsonResponse(exchange);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        stubRedisCount(601L);

        filter.filter(exchange, chain).subscribe();

        // 刻意不是 429：全站约定「业务级错误一律 200 + 业务码」，前端只维护一套读法
        verify(response).setStatusCode(HttpStatus.OK);
        verify(response).writeWith(any());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Redis 异常 → 放行（fail-open），限流组件故障不该拖垮整条链路")
    void failsOpenOnRedisError() {
        ServerWebExchange exchange = exchange("/content/api/v1/article/list", "203.0.113.9", null, null);
        GatewayFilterChain chain = stubChain(exchange);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("redis down")));

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("getOrder → -1，限流先于鉴权（未认证的刷量请求不进下游业务）")
    void runsBeforeAuthorizeFilter() {
        assertEquals(-1, filter.getOrder());
    }

    // ==================== 敏感路径分流 ====================

    @Nested
    @DisplayName("敏感路径走严格阈值")
    class SensitivePaths {

        @Test
        @DisplayName("登录路径进入 sensitive 桶，与普通请求互不挤占")
        void loginUsesSensitiveBucket() {
            ServerWebExchange exchange = exchange("/user/api/v1/login", "203.0.113.9", null, null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().startsWith(SENSITIVE_PREFIX));
        }

        @Test
        @DisplayName("同一计数下：敏感阈值 60 已超被拒，普通阈值 600 仍放行")
        void sameCountRejectedForSensitiveButNotForCommon() {
            ServerWebExchange sensitive = exchange("/user/api/v1/token/refresh", "203.0.113.9", null, null);
            ServerHttpResponse sensitiveResponse = stubJsonResponse(sensitive);
            GatewayFilterChain sensitiveChain = mock(GatewayFilterChain.class);
            stubRedisCount(61L);

            filter.filter(sensitive, sensitiveChain).subscribe();

            verify(sensitiveResponse).setStatusCode(HttpStatus.OK);
            verify(sensitiveChain, never()).filter(any());

            ServerWebExchange common = exchange("/content/api/v1/article/list", "203.0.113.9", null, null);
            GatewayFilterChain commonChain = stubChain(common);

            filter.filter(common, commonChain).subscribe();

            verify(commonChain).filter(common);
        }
    }

    // ==================== 客户端 IP 解析 ====================

    @Nested
    @DisplayName("客户端 IP 解析（代理头防伪造）")
    class ClientIpResolution {

        @Test
        @DisplayName("直连对端不在受信网段 → 直接用对端地址，代理头一律不采信")
        void untrustedPeerIgnoresForwardedHeaders() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "203.0.113.7", "1.1.1.1", "2.2.2.2");
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":203.0.113.7:"));
        }

        @Test
        @DisplayName("受信代理 → 从右往左取第一个非受信地址（最左可能是客户端伪造的）")
        void trustedProxyTakesRightmostUntrustedHop() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "127.0.0.1", "6.6.6.6, 10.0.0.9", null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":6.6.6.6:"));
        }

        @Test
        @DisplayName("受信代理但代理头里全是受信/空值 → 退回 X-Real-IP")
        void fallsBackToRealIpHeader() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "10.1.2.3", "10.0.0.1, ", "198.51.100.4");
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":198.51.100.4:"));
        }

        @Test
        @DisplayName("受信代理且没有任何代理头 → 回落对端地址")
        void fallsBackToPeerAddress() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "192.168.1.5", null, null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":192.168.1.5:"));
        }

        @Test
        @DisplayName("拿不到对端地址 → 归入 unknown 桶，解析失败不等于不限流")
        void unknownWhenPeerAddressMissing() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", null, null, null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":unknown:"));
        }

        @Test
        @DisplayName("172.20.x 落在 172.16/12 私网段内 → 视为受信代理，采信代理头")
        void private172InsideRangeIsTrusted() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "172.20.0.1", "9.9.9.9", null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":9.9.9.9:"));
        }

        @Test
        @DisplayName("172.32.x 已出私网段 → 不算受信，代理头被忽略")
        void private172OutsideRangeIsUntrusted() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "172.32.0.1", "9.9.9.9", null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":172.32.0.1:"));
        }

        @Test
        @DisplayName("172.x 第二段非数字 → 判为非受信且不抛异常")
        void malformedSecondOctetIsUntrusted() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "172.abc.0.1", "9.9.9.9", null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":172.abc.0.1:"));
        }

        @Test
        @DisplayName("172. 结尾（没有第二段）→ 判为非受信")
        void missingSecondOctetIsUntrusted() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "172.", "9.9.9.9", null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":172.:"));
        }

        @Test
        @DisplayName("IPv6 回环（0:0:0:0:0:0:0:1）同样视为受信代理")
        void ipv6LoopbackIsTrusted() {
            ServerWebExchange exchange = exchange("/content/api/v1/x", "0:0:0:0:0:0:0:1", "6.6.6.6", null);
            GatewayFilterChain chain = stubChain(exchange);
            stubRedisCount(1L);

            filter.filter(exchange, chain).subscribe();

            assertTrue(capturedKey().contains(":6.6.6.6:"));
        }
    }

    // ==================== helpers ====================

    /** 构造请求：不同 remoteAddr / 代理头用于驱动 IP 解析的各个分支 */
    private ServerWebExchange exchange(String path, String remoteAddr, String xForwardedFor, String xRealIp) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (xForwardedFor != null) {
            headers.add("X-Forwarded-For", xForwardedFor);
        }
        if (xRealIp != null) {
            headers.add("X-Real-IP", xRealIp);
        }
        // 注意：socketAddress() 内部会建 mock 并 stubbing，必须先求值到局部变量。
        // 直接写进 thenReturn(...) 的参数里，Mockito 会判定"外层 stubbing 未完成又开了新的"。
        InetSocketAddress peer = socketAddress(remoteAddr);
        when(request.getURI()).thenReturn(URI.create("http://host" + path));
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(peer);
        when(exchange.getRequest()).thenReturn(request);
        return exchange;
    }

    /** 用 mock 的 InetAddress 给地址，便于构造 "172." 这类真实解析器不会接受的形态 */
    private InetSocketAddress socketAddress(String ip) {
        if (ip == null) {
            return null;
        }
        InetAddress inetAddress = mock(InetAddress.class);
        when(inetAddress.getHostAddress()).thenReturn(ip);
        InetSocketAddress socketAddress = mock(InetSocketAddress.class);
        when(socketAddress.getAddress()).thenReturn(inetAddress);
        return socketAddress;
    }

    private GatewayFilterChain stubChain(ServerWebExchange exchange) {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());
        return chain;
    }

    /** 让 reject() 能把 JSON 写出去（它要取 Headers / BufferFactory / writeWith） */
    private ServerHttpResponse stubJsonResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(exchange.getResponse()).thenReturn(response);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());
        when(response.writeWith(any())).thenReturn(Mono.empty());
        return response;
    }

    private void stubRedisCount(long count) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(count));
    }

    /** 取出实际下发的限流 key：形如 gateway:ratelimit:{common|sensitive}:{ip}:{yyyyMMddHHmm} */
    @SuppressWarnings("unchecked")
    private String capturedKey() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), captor.capture(), anyList());
        return captor.getValue().get(0);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
