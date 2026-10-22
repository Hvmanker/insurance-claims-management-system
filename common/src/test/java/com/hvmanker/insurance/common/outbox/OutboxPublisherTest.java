package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hvmanker.insurance.common.outbox.SyntheticIds.AGGREGATE_A;
import static com.hvmanker.insurance.common.outbox.SyntheticIds.AGGREGATE_B;
import static com.hvmanker.insurance.common.outbox.SyntheticIds.eventId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxPublisherTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private KafkaTemplate<String, String> kafkaTemplate;
    private String topic;

    @BeforeEach
    void setUp() {
        var context = OutboxInboxSpringHarness.context();
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        transactionTemplate = context.getBean(TransactionTemplate.class);
        kafkaTemplate = OutboxInboxTestSupport.newKafkaTemplate();
        OutboxInboxTestSupport.truncateTables(jdbcTemplate);
        topic = OutboxInboxTestSupport.freshTopicName("test.outbox.publisher");
        OutboxInboxTestSupport.createTopic(topic, 1);
    }

    @Test
    void publishesUnsentRowAndMarksSent() {
        UUID e1 = eventId(1);
        String payload = payload(AGGREGATE_A);
        commitRow(e1, AGGREGATE_A, payload);

        runCycle(defaultPublisher());

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            List<ConsumerRecord<String, String>> records =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1);
            assertEquals(1, records.size());
            assertEquals(payload, records.get(0).value());
        }

        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));
    }

    @Test
    void onlyHeaderIsEventId() {
        UUID e1 = eventId(1);
        commitRow(e1, AGGREGATE_A, payload(AGGREGATE_A));
        runCycle(defaultPublisher());

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            ConsumerRecord<String, String> record =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).get(0);
            assertEquals(1, record.headers().toArray().length);
            Header header = record.headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER);
            assertNotNull(header);
            assertEquals(
                    "00000000-0000-0000-0000-000000000101",
                    new String(header.value(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void publishesInIdOrder() {
        UUID[] eventIds = {
                eventId(1), eventId(2), eventId(3), eventId(4), eventId(5)
        };
        UUID[] aggregates = {
                AGGREGATE_A, AGGREGATE_B, AGGREGATE_A, AGGREGATE_B, AGGREGATE_A
        };
        for (int i = 0; i < eventIds.length; i++) {
            commitRow(eventIds[i], aggregates[i], payload(aggregates[i]));
        }

        runCycle(defaultPublisher());

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            List<ConsumerRecord<String, String>> records =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 5);
            assertEquals(5, records.size());
            for (int i = 0; i < 5; i++) {
                Header header = records.get(i).headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER);
                assertEquals(eventIds[i].toString(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void sentRowsNotRepublished() {
        UUID e1 = eventId(1);
        commitRow(e1, AGGREGATE_A, payload(AGGREGATE_A));
        OutboxPublisher publisher = defaultPublisher();
        runCycle(publisher);
        runCycle(publisher);
        runCycle(publisher);
        runCycle(publisher);

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }
    }

    @Test
    void sentAtSetOnlyAfterSendAcknowledged() throws Exception {
        UUID e1 = eventId(1);
        commitRow(e1, AGGREGATE_A, payload(AGGREGATE_A));

        OutboxMessageSender failingSender = record -> {
            throw new ExecutionException("send failed", new RuntimeException("boom"));
        };
        runCycle(new OutboxPublisher(
                jdbcTemplate, failingSender, OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY));

        assertNull(jdbcTemplate.queryForObject(
                "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));

        runCycle(defaultPublisher());

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));
    }

    @Test
    void advisoryLockHeldMeansNoSend() throws Exception {
        UUID e1 = eventId(1);
        commitRow(e1, AGGREGATE_A, payload(AGGREGATE_A));

        Connection lockConnection = jdbcTemplate.getDataSource().getConnection();
        lockConnection.setAutoCommit(false);
        try (var statement = lockConnection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
            var result = statement.executeQuery();
            result.next();
            assertTrue(result.getBoolean(1));
        }

        runCycle(defaultPublisher());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));

        lockConnection.commit();
        lockConnection.close();

        runCycle(defaultPublisher());
        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }
    }

    @Test
    void differentServiceKeysDoNotBlock() throws Exception {
        UUID e1 = eventId(1);
        commitRow(e1, AGGREGATE_A, payload(AGGREGATE_A));

        long k1 = 91_001L;
        long k2 = 91_002L;

        Connection lockConnection = jdbcTemplate.getDataSource().getConnection();
        lockConnection.setAutoCommit(false);
        try (var statement = lockConnection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, k1);
            var result = statement.executeQuery();
            result.next();
            assertTrue(result.getBoolean(1));
        }

        OutboxPublisher publisherK2 = new OutboxPublisher(
                jdbcTemplate,
                OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)),
                k2);
        runCycle(publisherK2);

        lockConnection.commit();
        lockConnection.close();

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }
    }

    @Test
    void failedCycleReleasesLock() {
        UUID e1 = eventId(1);
        commitRow(e1, AGGREGATE_A, payload(AGGREGATE_A));

        AtomicBoolean throwBeforeSend = new AtomicBoolean(true);
        OutboxMessageSender stub = record -> {
            if (throwBeforeSend.get()) {
                throw new IllegalStateException("before send");
            }
            OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)).send(record);
        };

        OutboxPublisher publisher = new OutboxPublisher(
                jdbcTemplate, stub, OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
        runCycle(publisher);
        assertNull(jdbcTemplate.queryForObject(
                "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));

        throwBeforeSend.set(false);
        runCycle(publisher);

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }
    }

    private OutboxPublisher defaultPublisher() {
        return new OutboxPublisher(
                jdbcTemplate,
                OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)),
                OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
    }

    private void runCycle(OutboxPublisher publisher) {
        transactionTemplate.executeWithoutResult(status -> publisher.publishPending());
    }

    private void commitRow(UUID eventId, UUID aggregateId, String payload) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                """
                INSERT INTO outbox (event_id, aggregate_id, topic, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                eventId,
                aggregateId,
                topic,
                payload));
    }

    private static String payload(UUID aggregateId) {
        return "{\"claimId\":\"" + aggregateId + "\",\"amount\":\"4000.00\"}";
    }
}
