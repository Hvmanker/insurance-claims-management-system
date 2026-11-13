package com.hvmanker.insurance.approval.config;

import com.hvmanker.insurance.common.inbox.InboxGuard;
import com.hvmanker.insurance.common.outbox.OutboxMessageSender;
import com.hvmanker.insurance.common.outbox.OutboxPublisher;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableScheduling
public class ApprovalMessagingConfig {
    private static final long OUTBOX_ADVISORY_LOCK_KEY = 0x1A03L;

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
        return new OutboxWriter(jdbcTemplate);
    }

    @Bean
    InboxGuard inboxGuard(JdbcTemplate jdbcTemplate) {
        return new InboxGuard(jdbcTemplate);
    }

    @Bean
    OutboxPublisher outboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> outboxKafkaTemplate,
            @Value("${insurance.outbox.send-timeout-ms:30000}") long sendTimeoutMs) {
        OutboxMessageSender sender = OutboxMessageSender.kafka(
                outboxKafkaTemplate,
                Duration.ofMillis(sendTimeoutMs));
        return new OutboxPublisher(jdbcTemplate, sender, OUTBOX_ADVISORY_LOCK_KEY);
    }

    @Bean
    KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> producerProperties = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProperties));
    }
}
