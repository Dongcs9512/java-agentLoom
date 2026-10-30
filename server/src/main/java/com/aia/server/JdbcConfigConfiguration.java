package com.aia.server;

import com.aia.config.JdbcConfigRepository;
import com.aia.core.ConfigRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 把 agent/skill 配置落到数据库。仅在 {@code aia.config.mode=jdbc} 时启用。
 */
@Configuration
@ConditionalOnProperty(prefix = "aia.config", name = "mode", havingValue = "jdbc")
public class JdbcConfigConfiguration {

    @Value("${aia.config.datasource.url}")
    private String url;

    @Value("${aia.config.datasource.username}")
    private String username;

    @Value("${aia.config.datasource.password:}")
    private String password;

    @Value("${aia.config.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String driverClassName;

    @Value("${aia.config.datasource.maximum-pool-size:5}")
    private int maximumPoolSize;

    @Bean
    public DataSource configDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setPoolName("aia-config");
        return new HikariDataSource(config);
    }

    @Bean
    public ConfigRepository configRepository(DataSource configDataSource) {
        // 构造时自动建表（CREATE TABLE IF NOT EXISTS），无需手工执行 DDL
        return new JdbcConfigRepository(configDataSource);
    }
}
