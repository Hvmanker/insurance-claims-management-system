package com.sunil.insurance.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlements")
public class Settlement {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID claimId;

    @Column(nullable = false, length = 40)
    private String policyNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private Instant settledAt;

    protected Settlement() {
    }

    public Settlement(UUID id, UUID claimId, String policyNumber, BigDecimal paidAmount, Instant settledAt) {
        this.id = id;
        this.claimId = claimId;
        this.policyNumber = policyNumber;
        this.paidAmount = paidAmount;
        this.settledAt = settledAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}

