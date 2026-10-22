package com.hvmanker.insurance.common.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxRecord(
        long id,
        UUID eventId,
        UUID aggregateId,
        String topic,
        String payload,
        Instant createdAt,
        Instant sentAt) {
}
