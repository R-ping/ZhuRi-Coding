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
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl("jdbc:postgresql://192.168.44.128:5432/leadnews_content");
        config.setUsername("postgres");
        config.setPassword("123456");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setInitializationFailTimeout(-1);
        return config;
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