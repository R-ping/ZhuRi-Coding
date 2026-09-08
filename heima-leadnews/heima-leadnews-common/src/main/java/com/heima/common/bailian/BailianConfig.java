package com.heima.common.bailian;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "bailian.dashscope")
public class BailianConfig {

    private String apiKey;
    private String apiHost;
    private String model;
    private String embeddingModel;
    /** 多模态（图文理解）模型 */
    private String visionModel;
    private Timeout timeout = new Timeout();
    private Retry retry = new Retry();

    @Data
    public static class Timeout {
        private int connect = 5000;
        private int read = 30000;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private long backoffDelay = 1000;
    }
}