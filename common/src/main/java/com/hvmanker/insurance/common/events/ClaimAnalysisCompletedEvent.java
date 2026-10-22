package com.hvmanker.insurance.common.events;

import java.util.List;
import java.util.UUID;

public record ClaimAnalysisCompletedEvent(UUID eventId, UUID claimId, List<String> flags) {
    public ClaimAnalysisCompletedEvent {
        if (flags == null) {
            flags = List.of();
        }
    }
}
