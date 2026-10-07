package com.zhuri.coding.app.gateway.session;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 运营后台会话的键名与路径约定（全部集中在这里，避免散落在过滤器、控制器、配置三处）。
 *
 * <p><b>为什么是"网关持有会话"而不是各服务各自读会话</b>：本项目的鉴权约定是
 * 「网关是唯一的认证点，下游只信任带 HMAC 签名的身份头」（见 {@code AuthorizeFilter} 与各服务的
 * {@code *TokenInterceptor}）。让网关把会话翻译成同一组身份头，下游 content / user 两个服务
 * 的鉴权代码一行都不用改，也不会多出"有时认 header、有时认 cookie"的第二条身份来源。
 *
 * <p><b>⚠️ 跨模块口头契约</b>：{@link #REVOKED_PREFIX} 必须与 user 服务
 * {@code AdminAccountServiceImpl.REVOKED_KEY_PREFIX} 逐字一致。网关是 WebFlux 应用，
 * 而 common 模块里有 servlet 组件（拦截器），让网关依赖 common 会把 MVC 拖进响应式应用，
 * 所以两个模块刻意不互相依赖 —— 代价就是这个键没有编译器兜底，改名必须两边一起改。
 */
public final class AdminSessionKeys {

    private AdminSessionKeys() {
    }

    /** 会话 Cookie 名。不用默认的 SESSION：避免与将来可能引入的其他会话混在一起 */
    public static final String COOKIE_NAME = "ZHURI_ADMIN_SESSION";

    /**
     * 会话最大空闲时长。
     *
     * <p>8 小时 ≈ 一个工作日，运营不用中途被踢出去重登；同时它也是"会话最长能活多久"的上界，
     * 比 C 端 accToken 的 1 小时长得多 —— 之所以敢放长，是因为服务端会话可以立刻撤销
     * （见 {@link #REVOKED_PREFIX}），而 JWT 只能等它自然过期。
     */
    public static final Duration MAX_IDLE = Duration.ofHours(8);

    /** 会话属性：运营账号ID（ap_admin_account.id） */
    public static final String ATTR_ACCOUNT_ID = "adminAccountId";
    /** 会话属性：登录名 */
    public static final String ATTR_USERNAME = "adminUsername";
    /** 会话属性：展示名（作为昵称下发给下游） */
    public static final String ATTR_NICK_NAME = "adminNickName";
    /** 会话属性：是否仍在使用初始口令 */
    public static final String ATTR_MUST_CHANGE_PASSWORD = "adminMustChangePassword";

    /**
     * 「账号已停用」撤销标记的键前缀。
     *
     * <p>⚠️ 必须与 user 服务 {@code AdminAccountServiceImpl.REVOKED_KEY_PREFIX} 逐字一致。
     */
    public static final String REVOKED_PREFIX = "admin:session:revoked:account:";

    /** 撤销标记的查询键 */
    public static String revokedKey(Object accountId) {
        return REVOKED_PREFIX + accountId;
    }

    /**
     * 需要走运营会话鉴权的路径前缀（**已含网关的服务前缀**，不带结尾斜杠）。
     *
     * <p>必须与两个服务各自的 {@code ADMIN_PATH_PATTERNS} 一一对齐：
     * content 侧是 {@code /api/v1/admin/**}、{@code /api/v1/course/review/**}、
     * {@code /api/v1/pins/admin/**}、{@code /api/v1/audit/appeal/review}；
     * user 侧是 {@code /api/v1/admin/**}。
     * 这里漏掉一个前缀的后果是"那个接口仍然认 accToken" —— 于是运营后端
     * 既能用会话访问、又能用 C 端 token 访问，隔离就白做了。
     *
     * <p>⚠️ 最后一条是<b>单条完整路径</b>，与 content 侧一致：{@code /api/v1/audit/appeal} 下
     * 还有两个 C 端接口（{@code /submit}、{@code /status}），按前缀整段切到运营鉴权会把它们弄坏。
     * {@link #isAdminPath} 的匹配口径是「等于该路径，或以它加一个斜杠开头」，所以这里写完整路径
     * 也只会命中终审这一个端点，不会把同前缀的兄弟路径带上。
     */
    public static final List<String> ADMIN_PATH_PREFIXES = List.of(
        "/content/api/v1/admin",
        "/content/api/v1/course/review",
        "/content/api/v1/pins/admin",
        "/content/api/v1/audit/appeal/review",
        "/user/api/v1/admin"
    );

    /**
     * 「仍在使用初始口令」时**仍然放行**的路径。
     *
     * <p>身份自述要能调（否则前端连"我是谁"都不知道，没法提示去改口令），
     * 改口令本身当然也要能调 —— 否则初始口令永远换不掉，强制改密就成了死锁。
     * 其余运营接口一律拒绝。
     */
    public static final Set<String> MUST_CHANGE_PASSWORD_ALLOWED = Set.of(
        "/user/api/v1/admin/me",
        "/user/api/v1/admin/me/password"
    );

    /** 改口令接口的完整路径：网关在它成功返回后清掉会话里的"待改口令"标记 */
    public static final String PASSWORD_CHANGE_PATH = "/user/api/v1/admin/me/password";

    /**
     * 运营会话接口自身的前缀（由网关自己处理，不转发给任何服务）。
     *
     * <p>刻意选一个**不落在任何网关路由前缀下**的路径：网关的路由只有
     * {@code /user/** /content/** /search/** /notification/** /reward/**}，
     * 所以 {@code /admin-session/**} 不会命中任何路由，请求直接交给网关自己的控制器。
     * 若把这个接口挂到 {@code /user/**} 下，它就会先经过路由过滤器再被转发出去，
     * 而登录时还没有身份头可注入 —— 那种实现要额外处理一堆边界。
     *
     * <p>不带结尾斜杠：它同时用作 {@code @RequestMapping} 的值，带斜杠会拼出双斜杠路径。
     */
    public static final String SESSION_ENDPOINT_PREFIX = "/admin-session";

    /** 是否属于运营会话接口 */
    public static boolean isSessionEndpoint(String path) {
        return path.equals(SESSION_ENDPOINT_PREFIX) || path.startsWith(SESSION_ENDPOINT_PREFIX + "/");
    }

    /**
     * 是否属于运营鉴权路径。
     *
     * <p>匹配口径是「等于该路径，或以『路径 + /』开头」，不用裸前缀：清单里既有目录
     * （{@code /content/api/v1/admin}）也有单条完整路径（{@code /content/api/v1/audit/appeal/review}），
     * 裸 {@code startsWith} 会把 {@code .../reviewXxx} 这类同前缀的兄弟路径一起划进运营鉴权 ——
     * 那些接口本来面向 C 端，被切过来就直接不可用了，而且不会有人立刻注意到。
     */
    public static boolean isAdminPath(String path) {
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否服务间内部路径，必须对公网一律拒绝。
     *
     * <p>这些路径本意是"只在集群内被调用"（例如 user 服务的 {@code /internal/admin/verify}），
     * 但它们仍然落在网关的路由前缀下 —— {@code /user/internal/admin/verify} 经
     * {@code Path=/user/**} 的 {@code StripPrefix=1} 之后正好就是 {@code /internal/admin/verify}，
     * 也就是**外部可达**。不显式拦一下，这个"内部"接口就只是名字叫内部而已。
     */
    public static boolean isInternalPath(String path) {
        return path.contains("/internal/");
    }
}
