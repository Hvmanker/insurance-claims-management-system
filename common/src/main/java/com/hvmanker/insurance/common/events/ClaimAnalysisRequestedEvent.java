package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimAnalysisRequestedEvent(
        UUID eventId,
        UUID claimId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal claimedAmount) {
}
