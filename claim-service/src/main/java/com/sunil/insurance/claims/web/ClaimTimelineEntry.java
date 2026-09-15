package com.sunil.insurance.claims.web;

import com.sunil.insurance.claims.domain.ClaimEvent;

import java.time.Instant;
import java.util.UUID;

public record ClaimTimelineEntry(UUID id, String eventType, String title, String detail, String actor, Instant occurredAt) {
    public static ClaimTimelineEntry from(ClaimEvent event) {
        return new ClaimTimelineEntry(event.getId(), event.getEventType(), event.getTitle(), event.getDetail(), event.getActor(), event.getOccurredAt());
    }
}
