package com.heima.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss.scan")
public class OssConfigForImageScan {

    /** 内容安全接入端点，如 green-cip.cn-beijing.aliyuncs.com */
    private String endpoint;

    /** 待审核图片所在 OSS Bucket 名称 */
    private String bucket;

    /** OSS Bucket 所在地域，如 cn-beijing */
    private String region = "cn-beijing";

    /** OSS 访问域名（不含 bucket 前缀），如 oss-cn-beijing.aliyuncs.com */
    private String ossDomain = "oss-cn-beijing.aliyuncs.com";
}
