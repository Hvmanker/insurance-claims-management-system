package com.hvmanker.insurance.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "decision_audit")
public class DecisionAudit {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID claimId;

    @Column(nullable = false, length = 128)
    private String actorUserId;

    @Column(nullable = false, length = 32)
    private String actorRole;

    @Column(nullable = false, length = 20)
    private String attemptedDecision;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(nullable = false, length = 64)
    private String reasonCode;

    @Column(nullable = false)
    private Instant at;

    protected DecisionAudit() {
    }

    public DecisionAudit(
            UUID id,
            UUID claimId,
            String actorUserId,
            String actorRole,
            String attemptedDecision,
            String outcome,
            String reasonCode,
            Instant at) {
        this.id = id;
        this.claimId = claimId;
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.attemptedDecision = attemptedDecision;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.at = at;
    }
}
