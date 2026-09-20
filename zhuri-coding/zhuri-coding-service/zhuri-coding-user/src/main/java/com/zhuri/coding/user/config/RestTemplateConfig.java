package com.heima.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置类
 * <p>
 * 开发环境关闭 SSL 证书校验，解决 "PKIX path building failed" 错误。
 * 生产环境请正确导入 CA 证书，不要使用此配置。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }
}