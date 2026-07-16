package com.sunil.insurance.approval.service;

import com.sunil.insurance.approval.domain.ApprovalDecision;
import com.sunil.insurance.approval.domain.ClaimApproval;
import com.sunil.insurance.approval.domain.ClaimApprovalRepository;
import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.common.events.ClaimRejectedEvent;
import com.sunil.insurance.common.events.ClaimSubmittedEvent;
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
            kafkaTemplate.send(CLAIM_APPROVED, event.claimId().toString(), new ClaimApprovedEvent(
                    event.claimId(),
                    event.policyNumber(),
                    event.estimatedAmount(),
                    "system",
                    decidedAt
            ));
            return;
        }

        approvalRepository.save(new ClaimApproval(
                event.claimId(),
                event.policyNumber(),
                event.estimatedAmount(),
                null,
                ApprovalDecision.REJECTED,
                "Manual review required for high-value claim",
                decidedAt
        ));
        kafkaTemplate.send(CLAIM_REJECTED, event.claimId().toString(), new ClaimRejectedEvent(
                event.claimId(),
                event.policyNumber(),
                "Manual review required for high-value claim",
                decidedAt
        ));
    }
}

