package com.zhuri.coding.app.gateway.filter;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.app.gateway.session.AdminSessionKeys;
import com.zhuri.coding.utils.common.AppJwtUtil;
import io.jsonwebtoken.Claims;
import io.micrometer.common.util.StringUtils;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizeFilter implements Ordered, GlobalFilter {

    /** 与下游服务 shared 的内部身份签名请求头名称（见 common 模块 InternalAuthSigner） */
    private static final String INTERNAL_SIGN_HEADER = "X-Internal-Sign";

    /** 与下游服务一致的签名固定前缀 */
    private static final String INTERNAL_SIGN_PREFIX = "zhuri-coding-internal-v1";

    /** 未登录/会话失效统一回 444：前端约定收到它就跳登录页 */
    private static final int UNAUTHENTICATED_STATUS = 444;

    /** 「仍在用初始口令」的业务码（HTTP 层 403），与前端约定 */
    private static final int CODE_MUST_CHANGE_PASSWORD = 3003;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 网关与下游共享的内部身份签名密钥（未配置则不写签名，下游按 fail-closed 拒绝信任身份头） */
    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    /**
     * 用于查「账号已停用」的撤销标记（键格式见 {@link AdminSessionKeys#REVOKED_PREFIX}）。
     *
     * <p>没有这一查，停用一个运营账号要等它的会话自然过期才生效（最长 8 小时）——
     * 比 C 端 accToken 的 1 小时还久，那是倒退。服务端会话相对 JWT 的核心好处就是
     * "能立刻撤销"，所以这个每请求一次的 Redis GET 必须留着。
     */
    private final ReactiveStringRedisTemplate redisTemplate;

    public AuthorizeFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动期自检：密钥缺失时网关不写签名、下游 fail-closed 拒绝信任身份头，
     * 表现为"所有需登录接口都返回未登录"。这里必须告警，避免被误判为业务缺陷。
     */
    @jakarta.annotation.PostConstruct
    public void warnIfSecretMissing() {
        if (StringUtils.isBlank(internalAuthSecret)) {
            log.error("app.internal-auth.secret 未配置：网关不会写入内部签名，下游将拒绝信任身份头，"
                + "所有需登录接口都会返回未登录。请设置 INTERNAL_AUTH_SECRET（网关与各服务必须一致）。");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1.获取request和response对象
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        String path = request.getURI().getPath();
        String accToken = request.getHeaders().getFirst("accToken");

        //1.4 运营会话接口（/admin-session/**）不该进到这个过滤器。
        //   这些路径刻意不落在任何网关路由前缀下，正常情况下由网关自身的 @Controller 处理：
        //   WebFlux 的 RequestMappingHandlerMapping 是 order=0，而 RoutePredicateHandlerMapping
        //   默认 order=1（spring.cloud.gateway.server.webflux.handler-mapping.order），
        //   全局过滤器只在后者的过滤链里执行 —— 所以会话接口根本走不到这一行。
        //   能走到这里，说明这个前提被破坏了（调小了 handler-mapping.order，或新增了一条覆盖
        //   /admin-session 的路由），此时继续往下走只会把登录请求转发进下游服务、返回一个
        //   与"账号密码错"混在一起的神秘 404。与其静默失败，不如直接报出配置异常。
        if (AdminSessionKeys.isSessionEndpoint(path)) {
            log.error("运营会话接口被路由接管，网关配置异常（会话接口应由网关自身的 @Controller 处理）: path={}", path);
            return respondJson(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "网关路由配置异常");
        }

        //1.5 服务间内部路径一律不对外提供。
        //   它们本意是"只在集群内被调用"（如 user 服务的 /internal/admin/verify），
        //   但 /user/internal/** 经 StripPrefix=1 之后正好映射到那里，也就**外部可达**。
        //   不显式拦一下，这个"内部"接口就只是名字叫内部而已。
        if (AdminSessionKeys.isInternalPath(path)) {
            return respondJson(response, HttpStatus.FORBIDDEN, 403, "内部接口不对外提供");
        }

        //1.6 运营后台路径：只认服务端会话，**刻意不认 accToken**。
        //   这一步是运营账号与 C 端账号彻底隔离的关键：若这里仍然接受 accToken，
        //   那么任何 C 端账号只要其 id 与某个持有角色的运营账号相同，就能调运营接口 ——
        //   而运营账号与 C 端账号是两套独立的 ID 空间，碰撞是必然会发生的事。
        //   代价是运营前端不再能复用 C 端的 token，这正是本次改造的目的。
        if (AdminSessionKeys.isAdminPath(path)) {
            return authorizeAdmin(exchange, chain, path, request, response);
        }

        //2.判断是否是登录/注册/token刷新/社交登录相关接口（放行）
        // 注意：使用精确前缀/后缀匹配，避免 path.contains() 被路径中包含关键词的任意请求绕过
        boolean isPublic = isPublicPath(path);

        if (isPublic) {
            // 公开接口：未登录也可访问（利于 SEO 与爬虫）。
            // 若请求携带了有效 accToken，仍解析并注入用户上下文，
            // 使下游服务（如文章详情 isDigg/isCollect/isFollow）能识别当前登录用户；
            // 无 token 或 token 无效则按匿名处理放行。
            if (StringUtils.isNotBlank(accToken)) {
                try {
                    Claims claimsBody = AppJwtUtil.getClaimsBody(accToken);
                    // utils 版 verifyToken 返回 int：<1 表示有效（-1 距过期>600s / 0 在刷新窗内），>=1 表示过期或异常
                    if (AppJwtUtil.verifyToken(claimsBody) < 1) {
                        Object userId = claimsBody.get("userId");
                        String nickName = (String) claimsBody.get("nickName");
                        String image = (String) claimsBody.get("image");
                        ServerHttpRequest serverHttpRequest = request.mutate().headers(httpHeaders -> {
                            httpHeaders.add("userId", userId.toString());
                            httpHeaders.add("nickName", encodeNickName(nickName));
                            httpHeaders.add("image", image != null ? image : "");
                            addInternalSign(httpHeaders, userId.toString(), nickName, image);
                        }).build();
                        exchange = exchange.mutate().request(serverHttpRequest).build();
                    }
                } catch (Exception e) {
                    // token 无效/过期，按匿名处理放行
                    log.warn("公开接口token解析失败，按匿名处理, path={}", path);
                }
            }
            return chain.filter(exchange);
        }

        //3.accToken不存在，或过期，或校验不通过，都放回444，
        // 前端捕获到444后，应调用 /api/v1/token/refresh 接口用refToken刷新双token，再重放请求
        //4.判断token是否存在
        if (StringUtils.isBlank(accToken)) {
            response.setStatusCode(HttpStatusCode.valueOf(444));
            return response.setComplete();
        }
        //5.判断token是否有效
        try {
            Claims claimsBody = AppJwtUtil.getClaimsBody(accToken);
            //是否是过期（utils 版 verifyToken 返回 int，>=1 表示过期或异常）
            int verify = AppJwtUtil.verifyToken(claimsBody);
            if (verify >= 1) {
                response.setStatusCode(HttpStatusCode.valueOf(444));
                return response.setComplete();
            }
            //获取用户信息
            Object userId = claimsBody.get("userId");
            String nickName = (String) claimsBody.get("nickName");
            String image = (String) claimsBody.get("image");
            //存储header中
            ServerHttpRequest serverHttpRequest = request.mutate().headers(httpHeaders -> {
                httpHeaders.add("userId", userId.toString());
                httpHeaders.add("nickName", encodeNickName(nickName));
                httpHeaders.add("image", image != null ? image : "");
                addInternalSign(httpHeaders, userId.toString(), nickName, image);
            }).build();
            //重置请求
            exchange = exchange.mutate().request(serverHttpRequest).build();
        } catch (Exception e) {
            log.error("app端jwt解析失败：" + e.getMessage());
            response.setStatusCode(HttpStatusCode.valueOf(444));
            return response.setComplete();
        }
        //6.放行
        return chain.filter(exchange);
    }

    /**
     * 对写入请求头的 nickName 进行 URL 编码，避免中文等非 ASCII 字符在 HTTP 请求头中乱码。
     * 为 null 时返回空字符串，保持原有的空值逻辑；编码失败时回退为原始值。
     */
    private String encodeNickName(String nickName) {
        if (nickName == null) {
            return "";
        }
        return URLEncoder.encode(nickName, StandardCharsets.UTF_8);
    }

    /**
     * 为下游信任的身份头写入 HMAC 签名（X-Internal-Sign）。
     * 签名基于【原始值】userId/nickName/image；下游用解码后的昵称验签。
     * 算法与 common 模块 InternalAuthSigner 保持一致（HMAC-SHA256，payload 固定前缀 + | 分隔原始值）。
     * 未配置内部签名密钥时不写签名（下游同时以 fail-closed 拒绝信任身份头，不会退化为可伪造）。
     */
    private void addInternalSign(org.springframework.http.HttpHeaders httpHeaders,
                                 String userId, String nickName, String image) {
        if (StringUtils.isBlank(internalAuthSecret)) {
            return;
        }
        String payload = INTERNAL_SIGN_PREFIX + "|" + userId + "|" + (nickName != null ? nickName : "")
            + "|" + (image != null ? image : "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(internalAuthSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            httpHeaders.add(INTERNAL_SIGN_HEADER, sign);
        } catch (Exception e) {
            log.error("内部身份签名计算失败, 跳过签名头", e);
        }
    }

    /**
     * 判断路径是否为公开接口（无需登录即可访问）。
     * 公开接口被放行时若携带有效 token 仍会注入用户上下文（见 filter 方法）。
     */
    private boolean isPublicPath(String path) {
        return path.equals("/api/v1/login")
            || path.equals("/api/v1/login_auth")
            || path.startsWith("/api/v1/oauth2/")
            || path.startsWith("/api/v1/token/")
            || path.startsWith("/user/api/v1/token/")
            || path.startsWith("/api/v1/login/")
            || path.startsWith("/user/api/v1/login/")
            || path.startsWith("/content/api/v1/pins/list")
            || path.startsWith("/content/api/v1/pins/circles")
            || path.startsWith("/content/api/v1/pins/sidebar")
            || path.startsWith("/content/api/v1/circle/recommend")
            || path.startsWith("/content/api/v1/circle/my")
            || path.startsWith("/content/api/v1/author/info")
            // 沸点详情页公开只读接口（未登录也可浏览沸点详情/评论列表，利于 SEO）
            // 注意：仅放行只读查询，发布/点赞/发表评论/分享等写接口仍须登录
            || path.startsWith("/content/api/v1/pins/comment/list")
            || path.matches("/content/api/v1/pins/\\d+")
            || path.startsWith("/content/api/v1/topics/")
            || path.startsWith("/content/api/v1/article/recommend")
            || (path.startsWith("/content/api/v1/article/")&&path.endsWith("/recommend"))
            || path.startsWith("/content/api/v1/tag/by-category")
            || path.startsWith("/content/api/v1/tag/category-top")
            || path.startsWith("/content/api/v1/article/load")
            // 圈子广场/圈子详情/分类列表公开只读（未登录也可浏览，利于 SEO）。
            // 注意：此处刻意**不使用裸前缀** "/content/api/v1/circle" —— 它会把 /{id}/join、/{id}/leave
            // 等写接口一并放行（含匿名调用），与"仅放行只读查询"的本意相悖。
            || path.startsWith("/content/api/v1/circle/square")
            || path.startsWith("/content/api/v1/circle/hot")
            || path.startsWith("/content/api/v1/circle/categories/")
            || path.matches("/content/api/v1/circle/\\d+")
            || path.matches("/content/api/v1/circle/\\d+/feed")
            // 文章详情页（FTL 服务端渲染）浏览器导航加载，无法携带 accToken，公开访问利于 SEO
            || path.startsWith("/content/article/")
            // 文章详情页共用交互脚本（静态资源）
            || path.startsWith("/content/article-static.js")
            // 文章详情页公开只读接口（未登录也可浏览正文/评论/推荐，利于 SEO 与爬虫）
            // 注意：仅放行只读查询，点赞/收藏/关注/发表评论/回复/点赞评论等写接口仍须登录
            // 阅读行为上报（浏览量累计）：后端已允许未登录浏览计数（匿名只累计 views，登录才参与等级/历史），故公开放行
            || path.startsWith("/content/api/v1/read_behavior")
            || path.startsWith("/content/api/v1/article/detail/")
            || (path.startsWith("/content/api/v1/article/")
                && (path.endsWith("/column")
                    || path.endsWith("/related")
                    || path.endsWith("/featured")))
            || (path.startsWith("/content/api/v1/comment/article/") && path.endsWith("/comments"))
            // 文章打赏公开只读接口（未登录也可查看打赏汇总/感谢名单，利于 SEO）
            // 注意：仅放行只读查询与支付页/回调；创建打赏订单（/tip/create）与作者收益（/tip/my-revenue）仍须登录
            || path.startsWith("/content/api/v1/tip/summary")
            || path.startsWith("/content/api/v1/tip/list")
            || path.startsWith("/content/api/v1/tip/pay/page")
            || path.startsWith("/content/api/v1/tip/notify")
            // 课程支付：支付页由浏览器新开标签页直接导航（无法携带 accToken），
            // 通知回调由支付宝服务器 POST（无 token），均需公开放行
            || path.startsWith("/content/api/v1/course/list")
            || path.startsWith("/content/api/v1/course/pay/page")
            || path.startsWith("/content/api/v1/course/pay/notify")
            || path.startsWith("/content/api/v1/course/my")
            || path.startsWith("/content/api/v1/course/detail")
            // 课程章节只读阅读接口（未登录也可读免费小册整本与付费小册的免费/试读小节）。
            // 注意：仅放行 {id}/detail 只读详情，章节的增改删/排序/投稿审核等写接口仍须登录；
            // 后端 getChapterDetail 已做付费非试读节的登录+已购校验，匿名只可读免费内容。
            || path.matches("/content/api/v1/course/chapter/\\d+/detail")
            // 成就勋章公开只读接口（未登录也可浏览他人主页勋章）
            || path.matches("/content/api/v1/user/\\d+/achievements")
            // 个人主页公开只读接口（未登录也可浏览他人主页基本信息/统计/等级及分栏内容）
            || path.startsWith("/content/api/v1/user/home/")
            // 个人主页动态时间线（未登录也可浏览他人动态；未带 userId 时取登录用户）
            || path.startsWith("/content/api/v1/user/dynamic")
            // 统一搜索公开只读接口（未登录也可搜索文章/课程/标签/用户，按 id_type 分发，利于 SEO 与浏览）。
            // 搜索为纯只读查询，无写接口，故公开放行。
            // 课程/标签/用户搜索已收敛进该统一入口，不再单独放行 content/user 的搜索路径。
            || path.startsWith("/search/api/v1/search")
            // 详情页 AI 摘要（只读展示，未登录也可浏览；生成有 IP 限频 + Redis 缓存 24h 兜底）
            || path.startsWith("/content/api/v1/ai/summary/")
            // 每日一题公开只读接口（未登录也可看榜单与题库，利于浏览与 SEO）
            // 注意：仅放行只读查询，今日题（/coding/today，含个性化难度与作答态）、
            // 作答提交（/coding/answer）、我的统计与出题投稿等接口仍须登录
            || path.startsWith("/content/api/v1/coding/ranking")
            || path.startsWith("/content/api/v1/coding/questions")
            // 站点 SEO 基础文件（robots.txt / sitemap.xml）：爬虫无 token，公开放行
            || path.startsWith("/content/robots.txt")
            || path.startsWith("/content/sitemap.xml");
    }

    /**
     * 运营路径的鉴权：把服务端会话翻译成与 token 路径**完全相同**的一组身份头。
     *
     * <p>下游服务（content / user）只认 {@code userId/nickName/image + X-Internal-Sign}，
     * 不关心这个身份是从 JWT 来的还是从会话来的。所以整个改造只动了网关这一层，
     * 两个服务的鉴权代码一行没改 —— 这是沿用本项目"网关是唯一认证点"这个约定的收益。
     *
     * <p>判定顺序（先便宜的、后需要 IO 的）：
     * <ol>
     *   <li>会话里有没有账号ID —— 没有直接 444，让前端跳登录页；</li>
     *   <li>账号有没有被停用（Redis 撤销标记）—— 停用就当场销毁会话并 444；</li>
     *   <li>是否仍在用初始口令 —— 是则除身份自述与改口令外一律 403；</li>
     *   <li>注入身份头放行。</li>
     * </ol>
     */
    private Mono<Void> authorizeAdmin(ServerWebExchange exchange, GatewayFilterChain chain, String path,
                                      ServerHttpRequest request, ServerHttpResponse response) {
        return exchange.getSession().flatMap(session -> {
            Object accountId = session.getAttribute(AdminSessionKeys.ATTR_ACCOUNT_ID);
            if (accountId == null) {
                response.setStatusCode(HttpStatusCode.valueOf(UNAUTHENTICATED_STATUS));
                return response.setComplete();
            }
            return redisTemplate.hasKey(AdminSessionKeys.revokedKey(accountId))
                .defaultIfEmpty(false)
                .flatMap(revoked -> {
                    if (Boolean.TRUE.equals(revoked)) {
                        log.info("运营账号已被停用，销毁其会话, accountId={}", accountId);
                        return session.invalidate().then(Mono.defer(() -> {
                            response.setStatusCode(HttpStatusCode.valueOf(UNAUTHENTICATED_STATUS));
                            return response.setComplete();
                        }));
                    }
                    if (mustChangePassword(session)
                        && !AdminSessionKeys.MUST_CHANGE_PASSWORD_ALLOWED.contains(path)) {
                        return respondJson(response, HttpStatus.FORBIDDEN, CODE_MUST_CHANGE_PASSWORD,
                            "当前仍在使用初始口令，请先修改口令后再使用运营后台");
                    }

                    String nickName = rawNickName(session);
                    ServerHttpRequest mutated = request.mutate().headers(headers -> {
                        headers.add("userId", String.valueOf(accountId));
                        headers.add("nickName", encodeNickName(nickName));
                        headers.add("image", "");
                        // ⚠️ 签名用【原始】昵称，下发用【URL 编码后】的 —— 下游解码后再验签，
                        //    两边必须一致，否则中文昵称会让所有运营请求被判定为身份不可信
                        addInternalSign(headers, String.valueOf(accountId), nickName, "");
                    }).build();

                    Mono<Void> downstream = chain.filter(exchange.mutate().request(mutated).build());
                    if (AdminSessionKeys.PASSWORD_CHANGE_PATH.equals(path)) {
                        // 改口令成功后清掉会话标记：下一次请求起不再被拦。
                        // 放在响应完成之后执行，是为了确认真成功了 —— 失败还清掉的话，
                        // 用户会被放行去用后台，而口令其实还是初始的。
                        return downstream.then(Mono.fromRunnable(() -> {
                            HttpStatusCode status = response.getStatusCode();
                            if (status != null && status.is2xxSuccessful()) {
                                session.getAttributes().remove(AdminSessionKeys.ATTR_MUST_CHANGE_PASSWORD);
                            }
                        }));
                    }
                    return downstream;
                });
        });
    }

    /** 会话里的展示名（原始值，未 URL 编码） */
    private String rawNickName(WebSession session) {
        Object nickName = session.getAttribute(AdminSessionKeys.ATTR_NICK_NAME);
        return nickName == null ? null : String.valueOf(nickName);
    }

    private boolean mustChangePassword(WebSession session) {
        return Boolean.TRUE.equals(session.getAttribute(AdminSessionKeys.ATTR_MUST_CHANGE_PASSWORD));
    }

    /**
     * 写一个带业务码的 JSON 响应体（HTTP 状态与业务码分开：HTTP 表达"请求被拒"，
     * 业务码告诉前端"为什么被拒、该怎么处理"）。
     */
    private Mono<Void> respondJson(ServerHttpResponse response, HttpStatus status, int code, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("data", null);
        try {
            DataBuffer buffer = response.bufferFactory().wrap(JSON.writeValueAsBytes(payload));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("写鉴权响应体失败", e);
            return response.setComplete();
        }
    }

    /**
     * 优先级设置  值越小  优先级越高
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
