package com.sunil.insurance.claims.config;

import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_SUBMITTED;

@Configuration
public class KafkaProducerConfig {
    @Bean
    NewTopic claimSubmittedTopic() {
        return TopicBuilder.name(CLAIM_SUBMITTED).partitions(3).replicas(1).build();
    }

    @Bean
    ClaimEventPublisher claimEventPublisher(KafkaTemplate<String, ClaimSubmittedEvent> kafkaTemplate) {
        return new ClaimEventPublisher(kafkaTemplate);
    }
}

