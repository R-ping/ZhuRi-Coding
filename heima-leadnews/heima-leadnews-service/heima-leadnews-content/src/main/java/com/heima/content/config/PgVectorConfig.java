package com.heima.content.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true", matchIfMissing = false)
public class PgVectorConfig {

    @Bean
    @ConfigurationProperties(prefix = "pgvector.datasource")
    public HikariConfig pgVectorHikariConfig() {
        // 连接信息全部由配置注入（pgvector.datasource.*，见 application.yml；支持 ${PGVECTOR_*} 环境变量覆盖），此处不再硬编码
        return new HikariConfig();
    }

    @Bean
    public DataSource pgVectorDataSource() {
        return new HikariDataSource(pgVectorHikariConfig());
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}