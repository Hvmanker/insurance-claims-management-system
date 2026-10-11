package com.hvmanker.insurance.notification.messaging;

import com.hvmanker.insurance.common.events.ClaimApprovedEvent;
import com.hvmanker.insurance.common.events.ClaimRejectedEvent;
import com.hvmanker.insurance.common.events.ClaimSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_SETTLED;

@Component
public class WorkflowNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(WorkflowNotificationListener.class);

    @KafkaListener(topics = CLAIM_APPROVED, groupId = "notification-service-approved")
    public void onApproved(ClaimApprovedEvent event) {
        log.info("Notification queued: claimId={}, eventType={}", event.claimId(), CLAIM_APPROVED);
    }

    @KafkaListener(topics = CLAIM_REJECTED, groupId = "notification-service-rejected")
    public void onRejected(ClaimRejectedEvent event) {
        log.info("Notification queued: claimId={}, eventType={}", event.claimId(), CLAIM_REJECTED);
    }

    @KafkaListener(topics = CLAIM_SETTLED, groupId = "notification-service-settled")
    public void onSettled(ClaimSettledEvent event) {
        log.info("Notification queued: claimId={}, eventType={}", event.claimId(), CLAIM_SETTLED);
    }
}

