package com.sunil.insurance.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimSettledEvent(
        UUID claimId,
        UUID settlementId,
        String policyNumber,
        BigDecimal paidAmount,
        Instant settledAt
) {
}

