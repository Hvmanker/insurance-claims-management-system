package com.hvmanker.insurance.common.events;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimAnalysisRequestedEvent(UUID eventId, UUID claimId, BigDecimal claimedAmount) {
}
