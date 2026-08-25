package com.sunil.insurance.claims.config;

import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import com.sunil.insurance.common.events.ManualClaimDecisionEvent;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.ExecutionException;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_SUBMITTED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_MANUAL_DECISION;

public class ClaimEventPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ClaimEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ClaimSubmittedEvent event) {
        publish(CLAIM_SUBMITTED, event.claimId().toString(), event, "claim submission");
    }

    public void publishManualDecision(ManualClaimDecisionEvent event) {
        publish(CLAIM_MANUAL_DECISION, event.claimId().toString(), event, "manual decision");
    }

    private void publish(String topic, String key, Object event, String operation) {
        try {
            kafkaTemplate.send(topic, key, event).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + operation, exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Unable to publish " + operation, exception);
        }
    }
}
