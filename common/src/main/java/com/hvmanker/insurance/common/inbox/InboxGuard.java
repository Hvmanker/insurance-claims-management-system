package com.hvmanker.insurance.common.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotency guard for Kafka consumers. Call {@link #firstTime(String, UUID)} inside the consumer's
 * transaction before handling an event. When it returns {@code false}, the event was already processed
 * and the caller should skip handling and log:
 * {@code duplicate event skipped claimId={} eventId={}}.
 * The insert runs in the handler's surrounding transaction ({@code MANDATORY} propagation), so a
 * handler failure rolls the dedupe row back as well.
 */
public class InboxGuard {

    private final JdbcTemplate jdbcTemplate;

    public InboxGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumerGroup, UUID eventId) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO processed_events (consumer_group, event_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
                consumerGroup,
                eventId);
        return inserted == 1;
    }
}
