package com.sunil.insurance.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimApprovedEvent(
        UUID claimId,
        String policyNumber,
        BigDecimal approvedAmount,
        String approver,
        Instant approvedAt
) {
}

