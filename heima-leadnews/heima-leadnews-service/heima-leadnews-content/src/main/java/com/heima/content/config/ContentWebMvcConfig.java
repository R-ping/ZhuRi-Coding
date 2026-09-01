package com.heima.content.config;

import com.heima.content.interceptor.ContentTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ContentWebMvcConfig implements WebMvcConfigurer {

    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ContentTokenInterceptor(internalAuthSecret)).addPathPatterns("/**");
    }
}
