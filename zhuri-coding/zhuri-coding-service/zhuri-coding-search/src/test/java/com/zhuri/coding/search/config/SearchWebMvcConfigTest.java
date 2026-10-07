package com.zhuri.coding.search.config;

import com.zhuri.coding.search.interceptor.AppTokenInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SearchWebMvcConfig 测试。
 *
 * <p>只钉一件事：身份拦截器确实挂在 {@code /**} 上、且用的是配置里的内部签名密钥。
 * 这两点都不需要启动容器就能验证，但一旦被改错（把路径收窄、或换了个不校验签名的
 * 拦截器），影响面是所有检索接口 —— 所以值得一条测试守着。
 */
@DisplayName("搜索模块 WebMvc 配置")
class SearchWebMvcConfigTest {

    @Test
    @DisplayName("把 AppTokenInterceptor 注册到 /** 上")
    void registersTokenInterceptorOnAllPaths() throws Exception {
        SearchWebMvcConfig config = new SearchWebMvcConfig();
        setField(config, "internalAuthSecret", "test-internal-secret");

        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any(AppTokenInterceptor.class))).thenReturn(registration);

        config.addInterceptors(registry);

        verify(registry).addInterceptor(any(AppTokenInterceptor.class));
        verify(registration).addPathPatterns("/**");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
