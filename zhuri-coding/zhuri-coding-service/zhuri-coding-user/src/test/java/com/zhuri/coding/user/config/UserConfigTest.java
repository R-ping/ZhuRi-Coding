package com.zhuri.coding.user.config;

import com.aliyun.oss.OSS;
import com.zhuri.coding.common.admin.AdminAuthInterceptor;
import com.zhuri.coding.user.interceptor.UserTokenInterceptor;
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
 * - UserWebMvcConfig：注册身份拦截器与运营鉴权拦截器，且顺序、路径范围都正确。
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
    @DisplayName("UserWebMvcConfig：注册身份拦截器（全路径）+ 运营鉴权拦截器（仅运营前缀）")
    void testWebMvcConfig() {
        ExposedRegistry registry = new ExposedRegistry();
        new UserWebMvcConfig().addInterceptors(registry);

        MappedInterceptor[] mapped = registry.listAll();
        assertNotNull(mapped);
        // 两个拦截器，且顺序不能反：运营鉴权要读身份拦截器写入的当前用户
        assertEquals(2, mapped.length);
        assertInstanceOf(UserTokenInterceptor.class, mapped[0].getInterceptor());
        assertInstanceOf(AdminAuthInterceptor.class, mapped[1].getInterceptor());

        assertNotNull(mapped[0].getPathPatterns());
        assertEquals(1, mapped[0].getPathPatterns().length);

        // 运营鉴权的路径范围必须与公开常量一致 —— 这个常量是「接口有没有被挂上鉴权」的唯一边界，
        // 少一条就等于一批运营接口裸奔（注解只有请求真的过拦截器才生效）
        assertArrayEquals(UserWebMvcConfig.ADMIN_PATH_PATTERNS, mapped[1].getPathPatterns());
    }
}