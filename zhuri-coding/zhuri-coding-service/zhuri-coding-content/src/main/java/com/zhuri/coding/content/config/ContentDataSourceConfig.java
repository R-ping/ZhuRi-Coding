package com.zhuri.coding.content.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 主数据源显式声明（MySQL）。
 *
 * <p>背景：{@link PgVectorConfig} 按 pgvector.enabled=true 会向容器注册第二个 DataSource，
 * 若本类不声明 @Primary 主源，Spring Boot 的 DataSource 自动配置会因"已存在用户 DataSource"而
 * 退出（back off），导致 MyBatis 主源被 pg 池抢占（表现为 MySQL 表的 SQL 在 pg 上执行报
 * relation does not exist）。声明本 Bean 后主源始终为 spring.datasource（MySQL），pg 仅供向量读写。
 *
 * <p>JdbcTemplate 同理：Boot 的 JdbcTemplateAutoConfiguration 在"已存在任意 JdbcOperations"时退位，
 * 而 {@link PgVectorConfig} 的 pgVectorJdbcTemplate 先行注册，导致容器内唯一 JdbcTemplate 变成 PG 的，
 * 所有未限定 @Qualifier 的 JdbcTemplate 注入点（如 AiPromptRegistryImpl 查 ap_ai_prompt）都打到 PG 上
 * 报 bad SQL grammar。此处显式补一个基于主源的 @Primary jdbcTemplate 兜住该缺口。
 */
@Configuration
public class ContentDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource contentPrimaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    /** 主数据源（MySQL）的 JdbcTemplate：有 @Qualifier 使用 pgVectorJdbcTemplate 的点不受影响 */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
