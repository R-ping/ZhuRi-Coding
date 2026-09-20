package com.heima.common.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 限流拦截器自动配置
 * 各服务模块通过 @ComponentScan 扫描 com.heima.common 包即可自动启用
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/**", "/wemedia/api/v1/**")
                .order(1);
    }
}