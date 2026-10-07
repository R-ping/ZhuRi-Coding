package com.zhuri.coding.app.gateway.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营会话的路径/键名约定回归。
 *
 * <p>这些常量看着像配置，其实是<b>鉴权边界</b>，而且错了都不会报错：
 * <ul>
 *   <li>{@link AdminSessionKeys#isAdminPath} 少一条前缀 → 那个接口仍然认 C 端 token，
 *       隔离白做；多划一条 → C 端接口被要求运营身份，直接不可用；</li>
 *   <li>{@link AdminSessionKeys#isInternalPath} 漏判 → {@code /user/internal/**} 经
 *       StripPrefix 之后正好落在服务内的"内部"接口上，也就是外部可达；</li>
 *   <li>{@link AdminSessionKeys#REVOKED_PREFIX} 与 user 侧写得不一致 → 停用账号踢不掉会话，
 *       而且症状是"停用了但还能用"，比不实现这个功能更难发现。</li>
 * </ul>
 */
class AdminSessionKeysTest {

    @Test
    @DisplayName("目录式前缀：覆盖自身与子路径")
    void directoryPrefixCoversSelfAndChildren() {
        assertTrue(AdminSessionKeys.isAdminPath("/content/api/v1/admin"));
        assertTrue(AdminSessionKeys.isAdminPath("/content/api/v1/admin/accounts"));
        assertTrue(AdminSessionKeys.isAdminPath("/user/api/v1/admin/me"));
        assertTrue(AdminSessionKeys.isAdminPath("/content/api/v1/course/review/123/approve"));
        assertTrue(AdminSessionKeys.isAdminPath("/content/api/v1/pins/admin/list"));
    }

    @Test
    @DisplayName("单条完整路径前缀：只命中它自己，不带上同前缀的兄弟路径")
    void fullPathPrefixDoesNotSwallowSiblings() {
        assertTrue(AdminSessionKeys.isAdminPath("/content/api/v1/audit/appeal/review"));
        // 这两个是 C 端接口，混进运营鉴权会让申诉人自己用不了
        assertFalse(AdminSessionKeys.isAdminPath("/content/api/v1/audit/appeal/submit"));
        assertFalse(AdminSessionKeys.isAdminPath("/content/api/v1/audit/appeal/status"));
        // 裸 startsWith 的经典翻车：/me 前缀会把 /meetings 一起划进来
        assertFalse(AdminSessionKeys.isAdminPath("/content/api/v1/audit/appeal/reviewXxx"));
        assertFalse(AdminSessionKeys.isAdminPath("/user/api/v1/administration"));
    }

    @Test
    @DisplayName("运营路径必须带服务前缀：网关看到的是 /user/... 与 /content/...")
    void adminPathRequiresServicePrefix() {
        // 网关是按服务名转发的，服务内路径不会裸着出现在这里；
        // 若哪天清单写成不带前缀的形式，鉴权就会静默失效（永远匹配不上）。
        for (String prefix : AdminSessionKeys.ADMIN_PATH_PREFIXES) {
            assertTrue(prefix.startsWith("/user/") || prefix.startsWith("/content/"),
                prefix + " 没有服务前缀，网关永远匹配不到，该前缀的鉴权会静默失效");
            assertFalse(prefix.endsWith("/"),
                prefix + " 以斜杠结尾：isAdminPath 的匹配口径是『等于它或它加斜杠开头』，"
                    + "带尾斜杠会连自己都匹配不上");
        }
    }

    @Test
    @DisplayName("服务间内部路径一律识别出来")
    void internalPathsAreRecognized() {
        assertTrue(AdminSessionKeys.isInternalPath("/user/internal/admin/verify"));
        assertTrue(AdminSessionKeys.isInternalPath("/content/internal/anything"));
        assertFalse(AdminSessionKeys.isInternalPath("/user/api/v1/admin/me"));
        // 只是名字里带 internal 的普通路径不该被误伤（contains 的分寸）
        assertFalse(AdminSessionKeys.isInternalPath("/content/api/v1/articles/internal-audit"));
    }

    @Test
    @DisplayName("会话接口前缀：这是网关自己处理的路径，不能带回车/双斜杠")
    void sessionEndpointPrefix() {
        assertFalse(AdminSessionKeys.SESSION_ENDPOINT_PREFIX.endsWith("/"),
            "它会同时用作 @RequestMapping 的值，带尾斜杠会拼出双斜杠路径");
        assertTrue(AdminSessionKeys.isSessionEndpoint("/admin-session"));
        assertTrue(AdminSessionKeys.isSessionEndpoint("/admin-session/login"));
        assertFalse(AdminSessionKeys.isSessionEndpoint("/admin-sessionX"));
        assertFalse(AdminSessionKeys.isSessionEndpoint("/user/api/v1/admin/me"));
        // 会话端点刻意不落在任何路由前缀下，否则登录请求会被先路由转发出去
        assertFalse(AdminSessionKeys.isAdminPath(AdminSessionKeys.SESSION_ENDPOINT_PREFIX + "/login"));
    }

    @Test
    @DisplayName("撤销标记键格式固定为 前缀 + 账号ID（网关与 user 侧逐字对齐的口头契约）")
    void revokedKeyFormat() {
        assertEquals("admin:session:revoked:account:", AdminSessionKeys.REVOKED_PREFIX);
        assertEquals("admin:session:revoked:account:1001", AdminSessionKeys.revokedKey(1001));
    }

    @Test
    @DisplayName("撤销标记的存活时长必须 ≥ 会话最大空闲时长，否则标记先过期 = 停用延迟生效")
    void revokeTtlCoversSessionIdleTime() {
        // user 侧写标记时用的是自己的常量，这里只能守住网关这一侧的自洽性：
        // 标记比会话先消失，就会出现"标记没了、会话还在"，停用就又变成延迟生效了。
        assertTrue(AdminSessionKeys.MAX_IDLE.compareTo(java.time.Duration.ofHours(8)) <= 0,
            "会话空闲超时被调大后，user 侧 REVOKE_TTL（12h）必须跟着调整，否则撤销标记会先过期");
    }
}
