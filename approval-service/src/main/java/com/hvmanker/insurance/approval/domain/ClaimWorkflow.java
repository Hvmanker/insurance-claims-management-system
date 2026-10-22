package com.hvmanker.insurance.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claim_workflow")
public class ClaimWorkflow {
    @Id
    private UUID claimId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 40)
    private String policyNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal claimedAmount;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ClaimWorkflow() {
    }

    public ClaimWorkflow(UUID claimId, String status, String policyNumber, BigDecimal claimedAmount, Instant updatedAt) {
        this.claimId = claimId;
        this.status = status;
        this.policyNumber = policyNumber;
        this.claimedAmount = claimedAmount;
        this.updatedAt = updatedAt;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public String getStatus() {
        return status;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }
}
