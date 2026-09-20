package com.zhuri.coding.app.gateway.filter;

import com.zhuri.coding.utils.common.AppJwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthorizeFilter 单元测试
 *
 * 覆盖网关鉴权全局过滤器：
 *  - 公开接口（未登录放行 / 带有效 token 注入用户头）；
 *  - 非公开接口缺失、无效、过期 token 返回 444；
 *  - 有效 token 注入 userId/nickName/image 请求头并放行；
 *  - JWT 解析异常按 444 拦截。
 */
@DisplayName("AuthorizeFilter 网关鉴权过滤器")
class AuthorizeFilterTest {

    private final AuthorizeFilter filter = new AuthorizeFilter();

    private ServerWebExchange exchange(String path, String accToken) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        if (accToken != null) {
            headers.add("accToken", accToken);
        }
        when(request.getURI()).thenReturn(java.net.URI.create("http://host" + path));
        when(request.getHeaders()).thenReturn(headers);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);
        when(response.setComplete()).thenReturn(Mono.empty());
        return exchange;
    }

    private ServerWebExchange setupInjection(ServerWebExchange exchange, ServerHttpRequest request, HttpHeaders captured) {
        ServerHttpRequest.Builder reqBuilder = mock(ServerHttpRequest.Builder.class);
        when(request.mutate()).thenReturn(reqBuilder);
        when(reqBuilder.headers(any())).thenAnswer(inv -> {
            Consumer<HttpHeaders> c = inv.getArgument(0);
            c.accept(captured);
            return reqBuilder;
        });
        when(reqBuilder.build()).thenReturn(request);

        ServerWebExchange.Builder exBuilder = mock(ServerWebExchange.Builder.class);
        when(exchange.mutate()).thenReturn(exBuilder);
        when(exBuilder.request(any(ServerHttpRequest.class))).thenReturn(exBuilder);
        when(exBuilder.build()).thenReturn(exchange);
        return exchange;
    }

    @Test
    @DisplayName("公开接口、无 token → 匿名放行")
    void testPublicNoToken() {
        ServerWebExchange exchange = exchange("/api/v1/login", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        verify(response, never()).setComplete();
    }

    @Test
    @DisplayName("统一搜索聚合接口公开只读、无 token（文章/课程/标签/用户按 idType 分发）→ 匿名放行")
    void testUnifiedSearchPublicNoToken() {
        // 同一路径 /search/api/v1/search，白名单仅按路径放行，与 idType 无关
        ServerWebExchange exchange = exchange("/search/api/v1/search", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        verify(response, never()).setComplete();
    }

    @Test
    @DisplayName("公开接口、带有效 token → 注入用户头后放行")
    void testPublicWithValidToken() {
        HttpHeaders captured = new HttpHeaders();
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("accToken", "tok");
        when(request.getURI()).thenReturn(java.net.URI.create("http://host/content/api/v1/pins/list"));
        when(request.getHeaders()).thenReturn(headers);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(mock(ServerHttpResponse.class));
        setupInjection(exchange, request, captured);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(claims.get("userId")).thenReturn(100L);
            when(claims.get("nickName")).thenReturn("张三");
            when(claims.get("image")).thenReturn("i.png");
            when(AppJwtUtil.getClaimsBody("tok")).thenReturn(claims);
            when(AppJwtUtil.verifyToken(claims)).thenReturn(-1);

            filter.filter(exchange, chain).subscribe();
        }

        assertEquals("100", captured.getFirst("userId"));
        assertEquals(URLEncoder.encode("张三", StandardCharsets.UTF_8), captured.getFirst("nickName"));
        assertEquals("i.png", captured.getFirst("image"));
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("公开接口、token 解析失败 → 按匿名放行")
    void testPublicInvalidToken() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("accToken", "bad");
        when(request.getURI()).thenReturn(java.net.URI.create("http://host/content/api/v1/pins/list"));
        when(request.getHeaders()).thenReturn(headers);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(mock(ServerHttpResponse.class));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            when(AppJwtUtil.getClaimsBody(any())).thenThrow(new RuntimeException("boom"));
            when(chain.filter(exchange)).thenReturn(Mono.empty());
            filter.filter(exchange, chain).subscribe();
        }

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("非公开接口、无 token → 返回 444")
    void testProtectedNoToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/course/create", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(response).setComplete();
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("非公开接口、token 无效（verifyToken=false）→ 返回 444")
    void testProtectedInvalidToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/course/create", "tok");
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(AppJwtUtil.getClaimsBody("tok")).thenReturn(claims);
            when(AppJwtUtil.verifyToken(claims)).thenReturn(1);

            filter.filter(exchange, chain).subscribe();
        }

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(response).setComplete();
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("非公开接口、JWT 解析异常 → 返回 444")
    void testProtectedParseError() {
        ServerWebExchange exchange = exchange("/content/api/v1/course/create", "tok");
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            when(AppJwtUtil.getClaimsBody("tok")).thenThrow(new RuntimeException("boom"));

            filter.filter(exchange, chain).subscribe();
        }

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(response).setComplete();
    }

    @Test
    @DisplayName("非公开接口、有效 token → 注入用户头并放行")
    void testProtectedValidToken() {
        HttpHeaders captured = new HttpHeaders();
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("accToken", "tok");
        when(request.getURI()).thenReturn(java.net.URI.create("http://host/content/api/v1/course/create"));
        when(request.getHeaders()).thenReturn(headers);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(mock(ServerHttpResponse.class));
        setupInjection(exchange, request, captured);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(claims.get("userId")).thenReturn(7L);
            when(claims.get("nickName")).thenReturn("小明");
            when(claims.get("image")).thenReturn(null);
            when(AppJwtUtil.getClaimsBody("tok")).thenReturn(claims);
            when(AppJwtUtil.verifyToken(claims)).thenReturn(-1);

            filter.filter(exchange, chain).subscribe();
        }

        assertEquals("7", captured.getFirst("userId"));
        assertEquals(URLEncoder.encode("小明", StandardCharsets.UTF_8), captured.getFirst("nickName"));
        assertEquals("", captured.getFirst("image"));
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("课程章节只读详情、无 token → 匿名放行（免费/试读阅读）")
    void testCourseChapterDetailPublicNoToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/course/chapter/2089278840963514370/detail", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        verify(response, never()).setComplete();
    }

    @Test
    @DisplayName("课程章节写接口、无 token → 拦截（返回 444）")
    void testCourseChapterWriteNoToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/course/chapter/create", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(response).setComplete();
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("getOrder → 返回 0（最高优先级）")
    void testOrder() {
        assertEquals(0, filter.getOrder());
    }
}