package com.hvmanker.insurance.common.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Appends outbox rows in the caller's surrounding transaction ({@code MANDATORY} propagation).
 * If that transaction rolls back, no outbox row is persisted.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;

    public OutboxWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, UUID aggregateId, String topic, String payloadJson) {
        jdbcTemplate.update(
                """
                INSERT INTO outbox (event_id, aggregate_id, topic, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                eventId,
                aggregateId,
                topic,
                payloadJson);
    }
}
