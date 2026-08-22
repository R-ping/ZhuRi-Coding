package com.heima.user.config;

import com.aliyun.oss.OSS;
import com.heima.user.interceptor.UserTokenInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * config 包配置类单元测试（bean 创建与拦截器注册）
 *
 * - OssClientConfig：基于 OssConfig 装配 OSS 客户端；
 * - RestTemplateConfig：返回带超时设置的 RestTemplate；
 * - PasswordConfig：返回可用的 BCrypt 编码器；
 * - UserWebMvcConfig：注册 UserTokenInterceptor 并匹配所有路径。
 */
@DisplayName("config 配置类")
class UserConfigTest {

    /** 暴露 InterceptorRegistry 的 protected getInterceptors() */
    static class ExposedRegistry extends InterceptorRegistry {
        MappedInterceptor[] listAll() {
            java.util.List<Object> list = getInterceptors();
            return list.toArray(new MappedInterceptor[0]);
        }
    }

    @Test
    @DisplayName("OssClientConfig：根据 OssConfig 创建 OSS 客户端")
    void testOssClientConfig() {
        OssConfig ossConfig = new OssConfig();
        ossConfig.setEndpoint("https://oss.aliyuncs.com");
        ossConfig.setAccessKeyId("ak");
        ossConfig.setAccessKeySecret("sk");

        OssClientConfig config = new OssClientConfig();
        try {
            java.lang.reflect.Field f = OssClientConfig.class.getDeclaredField("ossConfig");
            f.setAccessible(true);
            f.set(config, ossConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        OSS oss = config.ossClient();
        assertNotNull(oss);
    }

    @Test
    @DisplayName("RestTemplateConfig：返回带超时配置的 RestTemplate")
    void testRestTemplate() {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();
        assertNotNull(restTemplate);
    }

    @Test
    @DisplayName("PasswordConfig：返回可用的 BCrypt 编码器")
    void testPasswordEncoder() {
        BCryptPasswordEncoder encoder = new PasswordConfig().passwordEncoder();
        String hash = encoder.encode("secret");
        assertTrue(encoder.matches("secret", hash));
        assertFalse(encoder.matches("other", hash));
    }

    @Test
    @DisplayName("UserWebMvcConfig：注册 UserTokenInterceptor 并匹配所有路径")
    void testWebMvcConfig() {
        ExposedRegistry registry = new ExposedRegistry();
        new UserWebMvcConfig().addInterceptors(registry);

        MappedInterceptor[] mapped = registry.listAll();
        assertNotNull(mapped);
        assertEquals(1, mapped.length);
        assertInstanceOf(UserTokenInterceptor.class, mapped[0].getInterceptor());
        // addPathPatterns("/**") 后路径模式长度为 1
        assertNotNull(mapped[0].getPathPatterns());
        assertEquals(1, mapped[0].getPathPatterns().length);
    }
}