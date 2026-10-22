package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimApprovedEvent(
        UUID eventId,
        UUID decisionId,
        UUID claimId,
        String policyNumber,
        BigDecimal approvedAmount,
        BigDecimal claimedAmount,
        DecidedBy decidedBy,
        @Deprecated String approver,
        Instant approvedAt
) {
}
