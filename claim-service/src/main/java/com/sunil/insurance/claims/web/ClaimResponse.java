package com.sunil.insurance.claims.web;

import com.sunil.insurance.common.ClaimStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimResponse(
        UUID id,
        String policyNumber,
        String claimantName,
        String claimType,
        BigDecimal estimatedAmount,
        ClaimStatus status,
        Instant submittedAt
) {
}

