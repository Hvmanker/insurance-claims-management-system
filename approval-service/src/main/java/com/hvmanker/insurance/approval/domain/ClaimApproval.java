package com.hvmanker.insurance.approval.domain;

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

    @Column(unique = true)
    private UUID decisionId;

    @Column(length = 128)
    private String decidedByUserId;

    @Column(length = 32)
    private String decidedByRole;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ApprovalSource source;

    protected ClaimApproval() {
    }

    public ClaimApproval(
            UUID claimId,
            String policyNumber,
            BigDecimal requestedAmount,
            BigDecimal approvedAmount,
            ApprovalDecision decision,
            String reason,
            Instant decidedAt,
            UUID decisionId,
            String decidedByUserId,
            String decidedByRole,
            ApprovalSource source) {
        this.claimId = claimId;
        this.policyNumber = policyNumber;
        this.requestedAmount = requestedAmount;
        this.approvedAmount = approvedAmount;
        this.decision = decision;
        this.reason = reason;
        this.decidedAt = decidedAt;
        this.decisionId = decisionId;
        this.decidedByUserId = decidedByUserId;
        this.decidedByRole = decidedByRole;
        this.source = source;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public ApprovalDecision getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public String getDecidedByUserId() {
        return decidedByUserId;
    }

    public String getDecidedByRole() {
        return decidedByRole;
    }
}
