package com.heima.search.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticSearchConfig {

    private String host;
    private int port;

    @Bean
    public RestHighLevelClient client(){
        log.info("配置 Elasticsearch 连接: {}:{}", host, port);
        return new RestHighLevelClient(RestClient.builder(
                new HttpHost(
                        host,
                        port,
                        "http"
                )
        ));
    }

    @PostConstruct
    public void checkConnection() {
        // 仅打印连接信息，不执行实际健康检查，避免因 ES 未就绪导致启动失败
        log.info("Elasticsearch 配置: host={}, port={}", host, port);
    }
}