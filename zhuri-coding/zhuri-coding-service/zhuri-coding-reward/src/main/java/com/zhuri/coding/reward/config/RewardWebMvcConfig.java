package com.zhuri.coding.reward.config;

import com.zhuri.coding.reward.interceptor.RewardTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * reward 服务 MVC 配置
 *
 * 注册 RewardTokenInterceptor 到所有 /api/v1/** 路径：
 * - 外部请求携带 accToken，拦截器解析后向线程注入【可信 userId】（以 token 为准，忽略可伪造的 header/path）；
 * - 服务间 Feign 直连不带 accToken，线程保持为空，由各 Controller 按"内部调用"处理（如资产写接口仅内部可调）。
 */
@Configuration
public class RewardWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RewardTokenInterceptor())
            .addPathPatterns("/api/v1/**");
    }
}