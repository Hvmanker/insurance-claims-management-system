package com.sunil.insurance.common.events;

import java.time.Instant;
import java.util.UUID;

public record ClaimRejectedEvent(
        UUID claimId,
        String policyNumber,
        String reason,
        Instant rejectedAt
) {
}

