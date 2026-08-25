package com.sunil.insurance.approval.service;

import com.sunil.insurance.approval.domain.ApprovalDecision;
import com.sunil.insurance.approval.domain.ClaimApproval;
import com.sunil.insurance.approval.domain.ClaimApprovalRepository;
import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.common.events.ClaimRejectedEvent;
import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import com.sunil.insurance.common.events.ManualClaimDecisionEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.sunil.insurance.common.KafkaTopics.CLAIM_REJECTED;

@Service
public class ApprovalService {
    private final ClaimApprovalRepository approvalRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BigDecimal autoApprovalLimit;

    public ApprovalService(
            ClaimApprovalRepository approvalRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${claims.auto-approval-limit:10000.00}") BigDecimal autoApprovalLimit
    ) {
        this.approvalRepository = approvalRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.autoApprovalLimit = autoApprovalLimit;
    }

    @Transactional
    public void evaluate(ClaimSubmittedEvent event) {
        ClaimApproval existing = approvalRepository.findById(event.claimId()).orElse(null);
        if (existing != null) {
            publishDecision(event, existing.getDecision(), existing.getApprovedAmount(), existing.getReason(), existing.getDecidedAt());
            return;
        }

        Instant decidedAt = Instant.now();
        if (event.estimatedAmount().compareTo(autoApprovalLimit) <= 0) {
            approvalRepository.save(new ClaimApproval(
                    event.claimId(),
                    event.policyNumber(),
                    event.estimatedAmount(),
                    event.estimatedAmount(),
                    ApprovalDecision.APPROVED,
                    "Auto-approved within configured limit",
                    decidedAt
            ));
            publishDecision(event, ApprovalDecision.APPROVED, event.estimatedAmount(), "Auto-approved within configured limit", decidedAt);
            return;
        }

        // High-value claims stay in the agent queue until a manual decision arrives.
    }

    @Transactional
    public void recordManualDecision(ManualClaimDecisionEvent event) {
        if (approvalRepository.existsById(event.claimId())) {
            return;
        }
        ApprovalDecision decision = event.approved() ? ApprovalDecision.APPROVED : ApprovalDecision.REJECTED;
        approvalRepository.save(new ClaimApproval(
                event.claimId(), event.policyNumber(), event.estimatedAmount(),
                event.approved() ? event.estimatedAmount() : null, decision, event.decisionReason(), event.decidedAt()));
        if (event.approved()) {
            kafkaTemplate.send(CLAIM_APPROVED, event.claimId().toString(), new ClaimApprovedEvent(
                    event.claimId(), event.policyNumber(), event.estimatedAmount(), event.decidedBy(), event.decidedAt()));
            return;
        }
        kafkaTemplate.send(CLAIM_REJECTED, event.claimId().toString(), new ClaimRejectedEvent(
                event.claimId(), event.policyNumber(), event.decisionReason(), event.decidedAt()));
    }

    private void publishDecision(ClaimSubmittedEvent event, ApprovalDecision decision, BigDecimal approvedAmount, String reason, Instant decidedAt) {
        if (decision == ApprovalDecision.APPROVED) {
            kafkaTemplate.send(CLAIM_APPROVED, event.claimId().toString(), new ClaimApprovedEvent(
                    event.claimId(), event.policyNumber(), approvedAmount, "system", decidedAt));
            return;
        }
        kafkaTemplate.send(CLAIM_REJECTED, event.claimId().toString(), new ClaimRejectedEvent(
                event.claimId(), event.policyNumber(), reason, decidedAt));
    }
}
