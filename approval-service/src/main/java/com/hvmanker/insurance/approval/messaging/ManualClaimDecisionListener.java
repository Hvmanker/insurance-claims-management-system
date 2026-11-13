package com.hvmanker.insurance.approval.messaging;

import com.hvmanker.insurance.approval.domain.ApprovalDecision;
import com.hvmanker.insurance.approval.service.ApprovalService;
import com.hvmanker.insurance.common.events.DecidedBy;
import com.hvmanker.insurance.common.events.ManualClaimDecisionEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_MANUAL_DECISION;

/**
 * Interim bridge until 1A.5 removes this listener, recordManualDecision, the adapter path,
 * and the claim.manual-decision topic.
 */
@Component
public class ManualClaimDecisionListener {
    private static final String AGENT_ROLE = "AGENT";

    private final ApprovalService approvalService;

    public ManualClaimDecisionListener(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @KafkaListener(
            topics = CLAIM_MANUAL_DECISION,
            groupId = "approval-service-manual-decisions",
            containerFactory = "manualDecisionKafkaListenerContainerFactory")
    public void onManualDecision(ManualClaimDecisionEvent event) {
        ApprovalDecision action = event.approved() ? ApprovalDecision.APPROVED : ApprovalDecision.REJECTED;
        approvalService.handleManualClaimDecision(
                event.claimId(),
                action,
                new DecidedBy(event.decidedBy(), AGENT_ROLE),
                event.decisionReason());
    }
}
