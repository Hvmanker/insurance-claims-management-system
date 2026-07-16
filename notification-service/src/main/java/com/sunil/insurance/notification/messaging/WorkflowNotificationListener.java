package com.sunil.insurance.notification.messaging;

import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.common.events.ClaimRejectedEvent;
import com.sunil.insurance.common.events.ClaimSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_SETTLED;

@Component
public class WorkflowNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(WorkflowNotificationListener.class);

    @KafkaListener(topics = CLAIM_APPROVED, groupId = "notification-service-approved")
    public void onApproved(ClaimApprovedEvent event) {
        log.info("Notification queued: claim {} for policy {} approved for {}", event.claimId(), event.policyNumber(), event.approvedAmount());
    }

    @KafkaListener(topics = CLAIM_REJECTED, groupId = "notification-service-rejected")
    public void onRejected(ClaimRejectedEvent event) {
        log.info("Notification queued: claim {} for policy {} rejected: {}", event.claimId(), event.policyNumber(), event.reason());
    }

    @KafkaListener(topics = CLAIM_SETTLED, groupId = "notification-service-settled")
    public void onSettled(ClaimSettledEvent event) {
        log.info("Notification queued: claim {} settled with payment {}", event.claimId(), event.paidAmount());
    }
}

