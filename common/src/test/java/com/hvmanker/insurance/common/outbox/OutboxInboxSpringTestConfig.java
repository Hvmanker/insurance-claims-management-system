package com.hvmanker.insurance.common.outbox;

import com.hvmanker.insurance.common.inbox.InboxGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
class OutboxInboxSpringTestConfig {

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
        return new OutboxWriter(jdbcTemplate);
    }

    @Bean
    InboxGuard inboxGuard(JdbcTemplate jdbcTemplate) {
        return new InboxGuard(jdbcTemplate);
    }
}
