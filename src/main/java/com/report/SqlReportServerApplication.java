package com.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排除 DataSourceAutoConfiguration：主连接池由 DataSourceService 统一管理，
 * 通过 PrimaryDataSourceConfig 暴露为 @Primary DataSource，避免双 Hikari 池。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableScheduling
public class SqlReportServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SqlReportServerApplication.class, args);
    }
}
