package com.zhuri.coding.app.gateway.filter;

import com.zhuri.coding.app.gateway.session.AdminSessionKeys;
import com.zhuri.coding.utils.common.AppJwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 *  - JWT 解析异常按 444 拦截；
 *  - <b>运营路径只认服务端会话</b>：C 端 token 一律不认（账号隔离的落点）。
 */
@DisplayName("AuthorizeFilter 网关鉴权过滤器")
class AuthorizeFilterTest {

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);

    private final AuthorizeFilter filter = new AuthorizeFilter(redisTemplate);

    /**
     * 撤销标记查询的默认值：**没有**停用标记。
     *
     * <p>刻意放在 {@code @BeforeEach} 而不是各用例里各写一遍：漏写会让 mock 返回 null，
     * 于是 flatMap 里的 NPE 被 subscribe() 吞掉，请求什么都没做 —— 这时
     * {@code verify(chain, never()).filter(...)} 这类断言会<b>空跑通过</b>，
     * 测试看起来是绿的却什么都没验。给一个"正常账号"的默认值，把"忘了 stub"变成不可能。
     */
    @BeforeEach
    void defaultNoRevocationMarker() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));
    }

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
    @DisplayName("每日一题榜单/题库公开只读、无 token → 匿名放行")
    void testCodingReadOnlyPublicNoToken() {
        for (String path : new String[]{"/content/api/v1/coding/ranking", "/content/api/v1/coding/questions"}) {
            ServerWebExchange exchange = exchange(path, null);
            ServerHttpResponse response = exchange.getResponse();
            GatewayFilterChain chain = mock(GatewayFilterChain.class);
            when(chain.filter(any())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).subscribe();

            verify(chain).filter(any());
            verify(response, never()).setComplete();
        }
    }

    @Test
    @DisplayName("每日一题今日题/作答、无 token → 返回 444（写接口与个性化接口不放行）")
    void testCodingDailyProtectedNoToken() {
        for (String path : new String[]{"/content/api/v1/coding/today", "/content/api/v1/coding/answer", "/content/api/v1/coding/stat"}) {
            ServerWebExchange exchange = exchange(path, null);
            ServerHttpResponse response = exchange.getResponse();
            GatewayFilterChain chain = mock(GatewayFilterChain.class);
            when(chain.filter(any())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).subscribe();

            verify(response).setStatusCode(HttpStatusCode.valueOf(444));
            verify(chain, never()).filter(any());
        }
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
    @DisplayName("圈子 feed 只读、无 token → 匿名放行")
    void testCircleFeedPublicNoToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/circle/1/feed", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        verify(response, never()).setComplete();
    }

    @Test
    @DisplayName("圈子详情只读、无 token → 匿名放行")
    void testCircleDetailPublicNoToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/circle/123", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(chain).filter(exchange);
        verify(response, never()).setComplete();
    }

    @Test
    @DisplayName("圈子写接口（join）、无 token → 拦截（返回 444）")
    void testCircleJoinWriteNoToken() {
        // 回归：曾用裸前缀 /content/api/v1/circle 放行，把 join/leave 一并公开，此处守住边界
        ServerWebExchange exchange = exchange("/content/api/v1/circle/1/join", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(response).setComplete();
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("圈子写接口（leave）、无 token → 拦截（返回 444）")
    void testCircleLeaveWriteNoToken() {
        ServerWebExchange exchange = exchange("/content/api/v1/circle/1/leave", null);
        ServerHttpResponse response = exchange.getResponse();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(response).setComplete();
        verify(chain, never()).filter(any());
    }

    // ==================== 运营后台路径：只认服务端会话 ====================

    /**
     * 给请求挂上一个 WebSession。
     *
     * @param accountId 会话里的运营账号ID；null 表示"没有会话"（未登录）
     */
    private WebSession setUpAdminSession(ServerWebExchange exchange, Object accountId, String nickName,
                                        Boolean mustChangePassword) {
        WebSession session = mock(WebSession.class);
        when(exchange.getSession()).thenReturn(Mono.just(session));
        when(session.getAttribute(AdminSessionKeys.ATTR_ACCOUNT_ID)).thenReturn(accountId);
        when(session.getAttribute(AdminSessionKeys.ATTR_NICK_NAME)).thenReturn(nickName);
        when(session.getAttribute(AdminSessionKeys.ATTR_MUST_CHANGE_PASSWORD)).thenReturn(mustChangePassword);
        when(session.invalidate()).thenReturn(Mono.empty());
        return session;
    }

    /** 让 respondJson 能写完（它要拿 Headers、BufferFactory 与 writeWith） */
    private void stubJsonResponse(ServerHttpResponse response) {
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());
        when(response.writeWith(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("运营路径没有会话 → 444，且压根不去解析 C 端 token（哪怕它有效）")
    void adminPathDoesNotAcceptClientToken() {
        ServerWebExchange exchange = exchange("/user/api/v1/admin/me", "tok");
        ServerHttpResponse response = exchange.getResponse();
        setUpAdminSession(exchange, null, null, null);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            filter.filter(exchange, chain).subscribe();
            // 这条才是"账号隔离"的落点：一旦运营路径去解析 accToken，就等于承认 C 端账号可以当运营身份用，
            // 而两套 ID 空间里的数字是会重合的。
            jwt.verify(() -> AppJwtUtil.getClaimsBody(any()), never());
        }

        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("运营路径会话有效 → 用运营账号ID注入身份头并放行（降级为 C 端 token 会在这里被看出来）")
    void adminPathInjectsAccountIdentity() {
        HttpHeaders captured = new HttpHeaders();
        ServerWebExchange exchange = exchange("/user/api/v1/admin/accounts", null);
        setUpAdminSession(exchange, 1001, "运营小王", Boolean.FALSE);
        setupInjection(exchange, exchange.getRequest(), captured);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        assertEquals("1001", captured.getFirst("userId"));
        // 下发的是 URL 编码后的昵称，签名用原始值 —— 下游解码后再验签，中文昵称才不会全军覆没
        assertEquals(URLEncoder.encode("运营小王", StandardCharsets.UTF_8), captured.getFirst("nickName"));
        assertEquals("", captured.getFirst("image"));
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("账号已停用（撤销标记存在）→ 就地销毁会话并返回 444，不必等它自然过期")
    void revokedAccountSessionIsDestroyed() {
        ServerWebExchange exchange = exchange("/user/api/v1/admin/accounts", null);
        WebSession session = setUpAdminSession(exchange, 1001, "运营小王", Boolean.FALSE);
        ServerHttpResponse response = exchange.getResponse();
        when(redisTemplate.hasKey(AdminSessionKeys.revokedKey(1001))).thenReturn(Mono.just(true));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(session).invalidate();
        verify(response).setStatusCode(HttpStatusCode.valueOf(444));
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("仍在使用初始口令 → 除身份自述与改口令外一律 403（业务码 3003），否则强制改密形同虚设")
    void mustChangePasswordBlocksOtherAdminPaths() {
        ServerWebExchange exchange = exchange("/user/api/v1/admin/accounts", null);
        setUpAdminSession(exchange, 1001, "运营小王", Boolean.TRUE);
        ServerHttpResponse response = exchange.getResponse();
        stubJsonResponse(response);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        verify(response).writeWith(any());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("仍在使用初始口令 + 改口令接口本身 → 放行（否则初始口令永远换不掉，成了死锁）")
    void mustChangePasswordAllowsSelfServicePaths() {
        HttpHeaders captured = new HttpHeaders();
        ServerWebExchange exchange = exchange(AdminSessionKeys.PASSWORD_CHANGE_PATH, null);
        setUpAdminSession(exchange, 1001, "运营小王", Boolean.TRUE);
        setupInjection(exchange, exchange.getRequest(), captured);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).subscribe();

        assertEquals("1001", captured.getFirst("userId"));
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("服务间内部路径一律不对外提供（/user/internal/** 经 StripPrefix 后正好落到 @internal 上）")
    void internalPathIsForbidden() {
        ServerWebExchange exchange = exchange("/user/internal/admin/verify", null);
        ServerHttpResponse response = exchange.getResponse();
        stubJsonResponse(response);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("运营会话接口若被路由接管 → 报配置异常，绝不转发（否则登录请求会神秘 404）")
    void sessionEndpointIsNeverForwarded() {
        // 正常情况这些路径由网关自身的 @Controller 处理（RequestMappingHandlerMapping order=0
        // 优先于 RoutePredicateHandlerMapping 默认 order=1），压根不会进这个过滤器。
        // 这条用例守的是"前提被破坏时要说出来"：宁可 500 + 明确日志，也不要 404 让人去查登录逻辑。
        ServerWebExchange exchange = exchange(AdminSessionKeys.SESSION_ENDPOINT_PREFIX + "/login", null);
        ServerHttpResponse response = exchange.getResponse();
        stubJsonResponse(response);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).subscribe();

        verify(response).setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(response).writeWith(any());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("申诉终审走运营会话，同前缀的提交/查询仍是 C 端接口")
    void appealReviewIsAdminGatedButSubmitIsNot() {
        // 终审：带有效 C 端 token 也没用，没有运营会话就是 444
        ServerWebExchange review = exchange("/content/api/v1/audit/appeal/review", "tok");
        setUpAdminSession(review, null, null, null);
        ServerHttpResponse reviewResponse = review.getResponse();
        GatewayFilterChain reviewChain = mock(GatewayFilterChain.class);

        filter.filter(review, reviewChain).subscribe();

        verify(reviewResponse).setStatusCode(HttpStatusCode.valueOf(444));
        verify(reviewChain, never()).filter(any());

        // 同前缀的提交：不在运营清单里，仍然走 C 端 token，注入的是 C 端用户ID
        HttpHeaders captured = new HttpHeaders();
        ServerWebExchange submit = exchange("/content/api/v1/audit/appeal/submit", "tok");
        setupInjection(submit, submit.getRequest(), captured);
        GatewayFilterChain submitChain = mock(GatewayFilterChain.class);
        when(submitChain.filter(any())).thenReturn(Mono.empty());

        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(claims.get("userId")).thenReturn(555L);
            when(claims.get("nickName")).thenReturn("普通用户");
            when(claims.get("image")).thenReturn(null);
            when(AppJwtUtil.getClaimsBody("tok")).thenReturn(claims);
            when(AppJwtUtil.verifyToken(claims)).thenReturn(-1);

            filter.filter(submit, submitChain).subscribe();
        }

        assertEquals("555", captured.getFirst("userId"));
        verify(submitChain).filter(any());
    }

    @Test
    @DisplayName("getOrder → 返回 0（最高优先级）")
    void testOrder() {
        assertEquals(0, filter.getOrder());
    }
}