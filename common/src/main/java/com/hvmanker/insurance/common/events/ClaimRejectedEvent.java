package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimRejectedEvent(
        UUID eventId,
        UUID decisionId,
        UUID claimId,
        String policyNumber,
        String reason,
        DecidedBy decidedBy,
        Instant rejectedAt
) {
}
