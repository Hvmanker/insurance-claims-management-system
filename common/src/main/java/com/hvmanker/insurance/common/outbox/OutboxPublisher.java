package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls unsent outbox rows and publishes them to Kafka. The message key is {@code aggregate_id}
 * (the claim id) as a string so all events for a claim stay ordered on one partition. The only
 * record header is {@code eventId}. {@code sent_at} is set only after the broker acknowledges
 * the send ({@code Future#get} completes successfully).
 */
public class OutboxPublisher {

    private static final String EVENT_ID_HEADER = "eventId";

    private static final RowMapper<OutboxRecord> OUTBOX_ROW_MAPPER = (ResultSet rs, int rowNum) -> new OutboxRecord(
            rs.getLong("id"),
            rs.getObject("event_id", UUID.class),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("topic"),
            rs.getString("payload"),
            rs.getObject("created_at", Instant.class),
            rs.getObject("sent_at", Instant.class));

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long advisoryLockKey;
    private final Duration sendTimeout;

    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            long advisoryLockKey,
            Duration sendTimeout) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.advisoryLockKey = advisoryLockKey;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${insurance.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        Boolean locked = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                advisoryLockKey);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        List<OutboxRecord> pending = jdbcTemplate.query(
                """
                SELECT id, event_id, aggregate_id, topic, payload::text AS payload, created_at, sent_at
                FROM outbox
                WHERE sent_at IS NULL
                ORDER BY id
                """,
                OUTBOX_ROW_MAPPER);

        for (OutboxRecord record : pending) {
            try {
                sendAndMarkSent(record);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException exception) {
                return;
            }
        }
    }

    private void sendAndMarkSent(OutboxRecord record)
            throws InterruptedException, ExecutionException, TimeoutException {
        String claimIdKey = record.aggregateId().toString();
        ProducerRecord<String, String> message = new ProducerRecord<>(
                record.topic(),
                claimIdKey,
                record.payload());
        message.headers().add(new RecordHeader(
                EVENT_ID_HEADER,
                record.eventId().toString().getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(message).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);

        jdbcTemplate.update(
                "UPDATE outbox SET sent_at = now() WHERE id = ?",
                record.id());
    }
}
