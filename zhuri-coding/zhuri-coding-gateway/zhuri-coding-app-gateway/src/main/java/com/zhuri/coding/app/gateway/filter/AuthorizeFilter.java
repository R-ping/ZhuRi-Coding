package com.zhuri.coding.app.gateway.filter;


import com.zhuri.coding.utils.common.AppJwtUtil;
import io.jsonwebtoken.Claims;
import io.micrometer.common.util.StringUtils;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizeFilter implements Ordered, GlobalFilter {

    /** 与下游服务 shared 的内部身份签名请求头名称（见 common 模块 InternalAuthSigner） */
    private static final String INTERNAL_SIGN_HEADER = "X-Internal-Sign";

    /** 与下游服务一致的签名固定前缀 */
    private static final String INTERNAL_SIGN_PREFIX = "zhuri-coding-internal-v1";

    /** 网关与下游共享的内部身份签名密钥（未配置则不写签名，下游按 fail-closed 拒绝信任身份头） */
    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

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
            || path.startsWith("/content/api/v1/circle")
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
            // 站点 SEO 基础文件（robots.txt / sitemap.xml）：爬虫无 token，公开放行
            || path.startsWith("/content/robots.txt")
            || path.startsWith("/content/sitemap.xml");
    }

    /**
     * 优先级设置  值越小  优先级越高
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
