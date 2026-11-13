package com.hvmanker.insurance.claims.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class KafkaProducerConfig {
    @Bean
    ClaimEventPublisher claimEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new ClaimEventPublisher(kafkaTemplate);
    }
}
