package com.zhuri.coding.user.config;

import com.zhuri.coding.common.admin.AdminAuthInterceptor;
import com.zhuri.coding.user.admin.LocalAdminRoleResolver;
import com.zhuri.coding.user.admin.UserAdminAuditRecorder;
import com.zhuri.coding.user.interceptor.UserTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class UserWebMvcConfig implements WebMvcConfigurer {

    /**
     * 纳入运营鉴权的路径前缀。
     *
     * <p>⚠️ <b>这个常量是鉴权的边界，也是回归测试的输入</b>：新开一个运营接口前缀却忘了加进来，
     * 那些接口就是<b>裸奔</b>（注解 {@code @RequireAdminPermission} 只有请求真的经过拦截器才生效）。
     * 所以它必须是 {@code public static final}，由 {@code AdminUserEndpointAnnotationTest}
     * 反射检查"每个运营控制器路径是否都被这里覆盖"。
     *
     * <p>反过来，加进前缀却不给 handler 挂注解不会放行、只会 403（fail-closed，
     * 见 {@code AdminAuthInterceptor}）—— 上线即暴露，比裸奔好得多。
     *
     * <p>⚠️ 与 content 侧一致：运营接口集中在本服务 {@code /api/v1/admin/**} 下，
     * 经网关访问时完整前缀是 {@code /user/api/v1/admin/**}。
     */
    public static final String[] ADMIN_PATH_PATTERNS = {
        "/api/v1/admin/**"
    };

    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    /**
     * 本服务直接查库解析运营角色（角色数据就在 leadnews_user），不需要 Feign，
     * 因此这里不像 content 侧那样需要 {@code @Lazy} 来打破循环依赖。
     */
    @Autowired
    private LocalAdminRoleResolver localAdminRoleResolver;

    @Autowired
    private UserAdminAuditRecorder userAdminAuditRecorder;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ⚠️ 顺序敏感：运营鉴权要读 AppThreadLocalUtil 里的当前用户，
        // 而它由 UserTokenInterceptor 写入，因此必须注册在其后（同序执行）。
        registry.addInterceptor(new UserTokenInterceptor(internalAuthSecret)).addPathPatterns("/**");
        registry.addInterceptor(new AdminAuthInterceptor(localAdminRoleResolver, userAdminAuditRecorder))
            .addPathPatterns(ADMIN_PATH_PATTERNS);
    }
}
