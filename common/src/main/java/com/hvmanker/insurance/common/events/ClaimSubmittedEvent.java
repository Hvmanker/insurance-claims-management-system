package com.hvmanker.insurance.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimSubmittedEvent(
        UUID claimId,
        String policyNumber,
        String claimantName,
        String claimType,
        BigDecimal estimatedAmount,
        Instant submittedAt
) {
}

