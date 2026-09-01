package com.heima.search.config;


import com.heima.search.interceptor.AppTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SearchWebMvcConfig implements WebMvcConfigurer {

    @Value("${app.internal-auth.secret:}")
    private String internalAuthSecret;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AppTokenInterceptor(internalAuthSecret)).addPathPatterns("/**");
    }
}
