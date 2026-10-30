package com.aia.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * DataSourceAutoConfiguration 被排除：数据源只在 aia.config.mode=jdbc 时由
 * {@link JdbcConfigConfiguration} 显式创建，否则框架不需要任何数据库也能启动。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentApplication.class, args);
    }
}
