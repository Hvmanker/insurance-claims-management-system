package com.sunil.insurance.claims.messaging;

import com.sunil.insurance.claims.service.ClaimService;
import com.sunil.insurance.common.ClaimStatus;
import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.common.events.ClaimRejectedEvent;
import com.sunil.insurance.common.events.ClaimSettledEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_SETTLED;

@Component
public class ClaimWorkflowListener {
    private final ClaimService claimService;

    public ClaimWorkflowListener(ClaimService claimService) {
        this.claimService = claimService;
    }

    @KafkaListener(topics = CLAIM_APPROVED, groupId = "claim-service-approved")
    public void onApproved(ClaimApprovedEvent event) {
        claimService.updateStatus(event.claimId(), ClaimStatus.APPROVED);
    }

    @KafkaListener(topics = CLAIM_REJECTED, groupId = "claim-service-rejected")
    public void onRejected(ClaimRejectedEvent event) {
        claimService.updateStatus(event.claimId(), ClaimStatus.REJECTED);
    }

    @KafkaListener(topics = CLAIM_SETTLED, groupId = "claim-service-settled")
    public void onSettled(ClaimSettledEvent event) {
        claimService.updateStatus(event.claimId(), ClaimStatus.SETTLED);
    }
}
