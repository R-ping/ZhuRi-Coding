package com.heima.content.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssConfig {

    private String accessKeyId;

    private String accessKeySecret;

    private String endpoint;

    private String host;

    private String bucket;

    private String dir;

    private Long expireTime = 300L;
}
