package com.heima.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss.scan")
public class OssConfigForImageScan {


    private String endpoint;

    private String bucket;
}
