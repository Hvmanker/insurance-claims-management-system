package com.sunil.insurance.claims.domain;

import com.sunil.insurance.common.ClaimStatus;
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
@Table(name = "claims")
public class Claim {
    @Id
    private UUID id;

    @Column(nullable = false, length = 40)
    private String policyNumber;

    @Column(nullable = false, length = 120)
    private String claimantName;

    @Column(nullable = false, length = 40)
    private String claimType;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status;

    @Column(nullable = false)
    private Instant submittedAt;

    protected Claim() {
    }

    public Claim(UUID id, String policyNumber, String claimantName, String claimType, BigDecimal estimatedAmount, ClaimStatus status, Instant submittedAt) {
        this.id = id;
        this.policyNumber = policyNumber;
        this.claimantName = claimantName;
        this.claimType = claimType;
        this.estimatedAmount = estimatedAmount;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public String getClaimType() {
        return claimType;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
