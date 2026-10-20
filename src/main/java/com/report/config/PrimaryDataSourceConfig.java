package com.report.config;

import com.report.service.DataSourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 将 Spring 主 DataSource 绑定到 DataSourceService 默认池，避免双 Hikari 连接池。
 * 需配合排除 DataSourceAutoConfiguration。
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceService dataSourceService) {
        return dataSourceService.getDataSource(null);
    }
}
