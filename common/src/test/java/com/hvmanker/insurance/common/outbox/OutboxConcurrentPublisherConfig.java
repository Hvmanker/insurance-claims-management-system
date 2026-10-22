package com.hvmanker.insurance.common.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
class OutboxConcurrentPublisherConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    OutboxPublisher outboxPublisher(JdbcTemplate jdbcTemplate, OutboxMessageSender outboxMessageSender) {
        return new OutboxPublisher(
                jdbcTemplate, outboxMessageSender, OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
    }
}
