package com.hvmanker.insurance.common.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Configuration
class OutboxCrashPublisherConfig {

    static final class MutableOutboxMessageSender implements OutboxMessageSender {

        private volatile OutboxMessageSender delegate;

        void setDelegate(OutboxMessageSender delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(OutboxRecord record) throws InterruptedException, ExecutionException, TimeoutException {
            delegate.send(record);
        }
    }

    @Bean
    MutableOutboxMessageSender mutableOutboxMessageSender() {
        return new MutableOutboxMessageSender();
    }

    @Bean
    OutboxPublisher outboxPublisher(JdbcTemplate jdbcTemplate, MutableOutboxMessageSender sender) {
        return new OutboxPublisher(
                jdbcTemplate, sender, OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
    }
}
