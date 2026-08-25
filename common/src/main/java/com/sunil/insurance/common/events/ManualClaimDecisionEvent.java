package com.sunil.insurance.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ManualClaimDecisionEvent(
        UUID claimId,
        String policyNumber,
        BigDecimal estimatedAmount,
        boolean approved,
        String decisionReason,
        String decidedBy,
        Instant decidedAt
) {
}
