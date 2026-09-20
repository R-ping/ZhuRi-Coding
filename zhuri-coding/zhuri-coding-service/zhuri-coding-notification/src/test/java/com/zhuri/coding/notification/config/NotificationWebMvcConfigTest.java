package com.heima.notification.config;

import com.heima.notification.interceptor.AppTokenInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationWebMvcConfig 单元测试
 *
 * 验证拦截器注册：向 InterceptorRegistry 添加 AppTokenInterceptor 并应用到 /** 路径。
 */
@DisplayName("NotificationWebMvcConfig Web MVC 配置")
class NotificationWebMvcConfigTest {

    @Test
    @DisplayName("addInterceptors → 注册 AppTokenInterceptor 到 /** 路径")
    void testAddInterceptors() {
        NotificationWebMvcConfig config = new NotificationWebMvcConfig();
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any(AppTokenInterceptor.class))).thenReturn(registration);
        when(registration.addPathPatterns("/**")).thenReturn(registration);

        config.addInterceptors(registry);

        verify(registry).addInterceptor(any(AppTokenInterceptor.class));
        verify(registration).addPathPatterns("/**");
    }
}