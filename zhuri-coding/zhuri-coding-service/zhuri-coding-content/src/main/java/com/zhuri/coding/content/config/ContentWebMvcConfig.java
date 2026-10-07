package com.zhuri.coding.content.config;

import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.common.admin.AdminAuthInterceptor;
import com.zhuri.coding.content.auth.AdminIdentityResolver;
import com.zhuri.coding.content.interceptor.ContentTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ContentWebMvcConfig implements WebMvcConfigurer {

    /**
     * 纳入运营鉴权的路径前缀（最后一条是完整路径，见下）。
     *
     * <p>为什么是「列出每个前缀」而不是只写 {@code /api/v1/admin/**}：本项目的运营接口并不是
     * 都挂在 {@code /admin} 下 —— 小册审核（{@code /api/v1/course/review}）、沸点管理
     * （{@code /api/v1/pins/admin}）与申诉终审是历史路径，改路径会连带改前端。它们的鉴权从
     * {@code EditorConfig} 代码白名单迁过来之后，路径保持不变、只是把"谁有权限"改由角色表决定。
     *
     * <p>⚠️ 最后一条是<b>单条完整路径</b>而不是前缀，这是刻意的：
     * {@code /api/v1/audit/appeal} 下还挂着两个 C 端接口（{@code /submit}、{@code /status}，
     * 申诉人自己用），按前缀整段纳入运营鉴权会让它们被要求运营身份而直接不可用。
     * 用 {@code /api/v1/audit/appeal/review} 这个具体路径，运营鉴权就只落在终审这一个端点上。
     *
     * <p>⚠️ 新增前缀必须同时给该前缀下的**每个** handler 加 {@code @RequireAdminPermission}，
     * 漏加不会放行、只会 403（fail-closed，见 {@code AdminAuthInterceptor}）。
     * 公开可见是有意为之：{@code AdminEndpointAnnotationTest} 靠它做「有没有漏挂路径」的回归。
     */
    public static final String[] ADMIN_PATH_PATTERNS = {
        "/api/v1/admin/**",
        "/api/v1/course/review/**",
        "/api/v1/pins/admin/**",
        "/api/v1/audit/appeal/review"
    };

    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    /**
     * ⚠️ 必须 {@code @Lazy}，否则整个应用起不来（不是性能优化，是打破循环依赖）。
     *
     * <p>依赖链会闭环：本类（WebMvcConfigurer）→ AdminIdentityResolver → IUserClient（Feign 客户端）
     * → 创建 Feign 客户端需要 {@code HttpMessageConverters} → {@code EnableWebMvcConfiguration}
     * → 它要收集全部的 {@code WebMvcConfigurer} → 又回到本类。
     * 表现为启动直接失败：{@code Requested bean is currently in creation: Is there an unresolvable
     * circular reference...}，且只在真正启动 Spring 上下文时暴露（编译、纯单测都看不出来）。
     *
     * <p>{@code @Lazy} 注入的是代理，构造本类时不去创建目标对象，回边即被切断；
     * 首次真正调用 {@code resolve}（请求进来时）才创建，那会儿上下文早已就绪。
     */
    @Lazy
    @Autowired
    private AdminIdentityResolver adminIdentityResolver;

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ⚠️ 顺序敏感：运营鉴权要读 AppThreadLocalUtil 里的当前用户，
        // 而它由 ContentTokenInterceptor 写入，因此必须注册在其后（同序执行）。
        registry.addInterceptor(new ContentTokenInterceptor(internalAuthSecret)).addPathPatterns("/**");
        registry.addInterceptor(new AdminAuthInterceptor(adminIdentityResolver, adminAuditRecorder))
            .addPathPatterns(ADMIN_PATH_PATTERNS);
    }
}
