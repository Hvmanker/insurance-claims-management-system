package com.sunil.insurance.approval.messaging;

import com.sunil.insurance.approval.service.ApprovalService;
import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import com.sunil.insurance.common.events.ManualClaimDecisionEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_SUBMITTED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_MANUAL_DECISION;

@Component
public class ClaimSubmittedListener {
    private final ApprovalService approvalService;

    public ClaimSubmittedListener(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @KafkaListener(topics = CLAIM_SUBMITTED, groupId = "approval-service")
    public void onClaimSubmitted(ClaimSubmittedEvent event) {
        approvalService.evaluate(event);
    }

    @KafkaListener(topics = CLAIM_MANUAL_DECISION, groupId = "approval-service-manual-decisions")
    public void onManualDecision(ManualClaimDecisionEvent event) {
        approvalService.recordManualDecision(event);
    }
}
