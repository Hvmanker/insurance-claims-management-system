package com.sunil.insurance.claims.config;

import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import org.springframework.kafka.core.KafkaTemplate;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_SUBMITTED;

public class ClaimEventPublisher {
    private final KafkaTemplate<String, ClaimSubmittedEvent> kafkaTemplate;

    public ClaimEventPublisher(KafkaTemplate<String, ClaimSubmittedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ClaimSubmittedEvent event) {
        kafkaTemplate.send(CLAIM_SUBMITTED, event.claimId().toString(), event);
    }
}

