package com.sunil.insurance.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claim_approvals")
public class ClaimApproval {
    @Id
    private UUID claimId;

    @Column(nullable = false, length = 40)
    private String policyNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedAmount;

    @Column(precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalDecision decision;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(nullable = false)
    private Instant decidedAt;

    protected ClaimApproval() {
    }

    public ClaimApproval(UUID claimId, String policyNumber, BigDecimal requestedAmount, BigDecimal approvedAmount, ApprovalDecision decision, String reason, Instant decidedAt) {
        this.claimId = claimId;
        this.policyNumber = policyNumber;
        this.requestedAmount = requestedAmount;
        this.approvedAmount = approvedAmount;
        this.decision = decision;
        this.reason = reason;
        this.decidedAt = decidedAt;
    }
}

