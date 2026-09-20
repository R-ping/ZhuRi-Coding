package com.heima.user.config;

import com.heima.user.interceptor.UserTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class UserWebMvcConfig implements WebMvcConfigurer {

    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserTokenInterceptor(internalAuthSecret)).addPathPatterns("/**");
    }
}
