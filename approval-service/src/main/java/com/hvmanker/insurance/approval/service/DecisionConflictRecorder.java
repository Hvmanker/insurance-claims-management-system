package com.hvmanker.insurance.approval.service;

import com.hvmanker.insurance.approval.domain.ApprovalDecision;
import com.hvmanker.insurance.approval.domain.DecisionAudit;
import com.hvmanker.insurance.approval.domain.DecisionAuditRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class DecisionConflictRecorder {
    public static final String OUTCOME_CONFLICT = "CONFLICT";
    public static final String REASON_CLAIM_ALREADY_DECIDED = "CLAIM_ALREADY_DECIDED";

    private final DecisionAuditRepository decisionAuditRepository;

    public DecisionConflictRecorder(DecisionAuditRepository decisionAuditRepository) {
        this.decisionAuditRepository = decisionAuditRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordHumanLostConflict(
            UUID claimId,
            String actorUserId,
            String actorRole,
            ApprovalDecision attemptedDecision) {
        decisionAuditRepository.save(new DecisionAudit(
                UUID.randomUUID(),
                claimId,
                actorUserId,
                actorRole,
                attemptedDecision.name(),
                OUTCOME_CONFLICT,
                REASON_CLAIM_ALREADY_DECIDED,
                Instant.now()));
    }
}
