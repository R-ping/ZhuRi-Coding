package com.heima.content.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OssClientConfig {

    @Autowired
    private OssConfig ossConfig;

    @Bean
    public OSS ossClient() {
        return new OSSClientBuilder().build(
            ossConfig.getEndpoint(),
            ossConfig.getAccessKeyId(),
            ossConfig.getAccessKeySecret()
        );
    }
}
