package com.hvmanker.insurance.common.outbox;

import com.hvmanker.insurance.common.inbox.InboxGuard;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.hvmanker.insurance.common.outbox.SyntheticIds.AGGREGATE_A;
import static com.hvmanker.insurance.common.outbox.SyntheticIds.eventId;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxCrashTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private KafkaTemplate<String, String> kafkaTemplate;
    private String topic;

    @BeforeEach
    void setUp() {
        OutboxInboxSpringHarness.context();
        jdbcTemplate = OutboxInboxSpringHarness.jdbcTemplate();
        transactionTemplate = OutboxInboxSpringHarness.context().getBean(TransactionTemplate.class);
        kafkaTemplate = OutboxInboxTestSupport.newKafkaTemplate();
        OutboxInboxTestSupport.truncateTables(jdbcTemplate);
        topic = OutboxInboxTestSupport.freshTopicName("test.outbox.crash");
        OutboxInboxTestSupport.createTopic(topic, 1);
    }

    @Test
    void killBeforeSendPublishesExactlyOnceAfterRestart() {
        UUID e1 = eventId(1);
        commitRow(e1);

        OutboxInboxSpringHarness.context();
        OutboxInboxSpringHarness.resetContext();

        AnnotationConfigApplicationContext secondContext = new AnnotationConfigApplicationContext();
        secondContext.getBeanFactory().registerSingleton("dataSource", OutboxInboxTestSupport.newDataSource());
        secondContext.register(OutboxInboxSpringTestConfig.class);
        secondContext.refresh();
        JdbcTemplate restartJdbc = secondContext.getBean(JdbcTemplate.class);
        TransactionTemplate restartTx = secondContext.getBean(TransactionTemplate.class);

        OutboxPublisher publisher = newPublisher(restartJdbc);
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            restartTx.executeWithoutResult(status -> publisher.publishPending());
            return restartJdbc.queryForObject(
                    "SELECT sent_at IS NOT NULL FROM outbox WHERE event_id = ?", Boolean.class, e1);
        });

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            List<ConsumerRecord<String, String>> records =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1);
            assertEquals(1, records.size());
            assertEquals(e1.toString(), headerEventId(records.get(0)));
        }

        for (int i = 0; i < 3; i++) {
            restartTx.executeWithoutResult(status -> publisher.publishPending());
        }

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }

        secondContext.close();
        OutboxInboxSpringHarness.resetContext();
        OutboxInboxSpringHarness.context();
    }

    @Test
    void midBatchSendFailureKeepsEarlierRowsAndResendsRestInOrder() {
        UUID e1 = eventId(1);
        UUID e2 = eventId(2);
        UUID e3 = eventId(3);
        commitRow(e1);
        commitRow(e2);
        commitRow(e3);

        OutboxMessageSender realSender = OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30));
        OutboxMessageSender failingStub = record -> {
            if (record.eventId().equals(e1)) {
                realSender.send(record);
                return;
            }
            if (record.eventId().equals(e2)) {
                throw new IllegalStateException("stub fails on e2");
            }
            realSender.send(record);
        };

        AnnotationConfigApplicationContext publisherContext = new AnnotationConfigApplicationContext();
        publisherContext
                .getBeanFactory()
                .registerSingleton("dataSource", OutboxInboxSpringHarness.context().getBean(DataSource.class));
        publisherContext.register(OutboxInboxSpringTestConfig.class, OutboxCrashPublisherConfig.class);
        publisherContext.refresh();

        try {
            OutboxCrashPublisherConfig.MutableOutboxMessageSender mutableSender =
                    publisherContext.getBean(OutboxCrashPublisherConfig.MutableOutboxMessageSender.class);
            OutboxPublisher publisher = publisherContext.getBean(OutboxPublisher.class);

            mutableSender.setDelegate(failingStub);
            // publishPending catches ExecutionException|TimeoutException|RuntimeException, logs WARN, and
            // returns without rethrowing so the Spring @Transactional boundary commits e1's sent_at.
            publisher.publishPending();

            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e2));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e3));

            try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
                List<ConsumerRecord<String, String>> afterFailure =
                        OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1);
                assertEquals(1, afterFailure.size());
                assertEquals(e1.toString(), headerEventId(afterFailure.get(0)));
            }

            mutableSender.setDelegate(realSender);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                publisher.publishPending();
                return jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL", Integer.class)
                        == 3;
            });

            try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
                List<ConsumerRecord<String, String>> records =
                        OutboxInboxTestSupport.consumeUntilQuiet(consumer, 3);
                assertEquals(3, records.size());
                assertEquals(e1.toString(), headerEventId(records.get(0)));
                assertEquals(e2.toString(), headerEventId(records.get(1)));
                assertEquals(e3.toString(), headerEventId(records.get(2)));
            }
        } finally {
            publisherContext.close();
        }
    }

    @Test
    void crashAfterSendBeforeMarkIsAtLeastOnceAndInboxDedups() {
        UUID e1 = eventId(1);
        commitRow(e1);

        OutboxMessageSender crashSender = record -> {
            OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)).send(record);
            throw new IllegalStateException("simulated crash before sent_at");
        };
        OutboxPublisher crashPublisher = new OutboxPublisher(
                jdbcTemplate, crashSender, OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
        transactionTemplate.executeWithoutResult(status -> crashPublisher.publishPending());

        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox WHERE event_id = ? AND sent_at IS NOT NULL",
                        Integer.class,
                        e1));

        OutboxPublisher recoveryPublisher = newPublisher(jdbcTemplate);
        transactionTemplate.executeWithoutResult(status -> recoveryPublisher.publishPending());

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            List<ConsumerRecord<String, String>> records =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 2);
            assertEquals(2, records.size());
            assertEquals(e1.toString(), headerEventId(records.get(0)));
            assertEquals(e1.toString(), headerEventId(records.get(1)));
        }

        InboxGuard inboxGuard = OutboxInboxSpringHarness.context().getBean(InboxGuard.class);
        Boolean first = transactionTemplate.execute(status -> inboxGuard.firstTime("test-group", e1));
        Boolean second = transactionTemplate.execute(status -> inboxGuard.firstTime("test-group", e1));
        assertTrue(first);
        assertFalse(second);
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                        Integer.class,
                        "test-group",
                        e1));
    }

    private OutboxPublisher newPublisher(JdbcTemplate jdbc) {
        return new OutboxPublisher(
                jdbc,
                OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)),
                OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY);
    }

    private void commitRow(UUID eventId) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                """
                INSERT INTO outbox (event_id, aggregate_id, topic, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                eventId,
                AGGREGATE_A,
                topic,
                "{\"claimId\":\"" + AGGREGATE_A + "\",\"amount\":\"4000.00\"}"));
    }

    private static String headerEventId(ConsumerRecord<String, String> record) {
        return new String(
                record.headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER).value(),
                StandardCharsets.UTF_8);
    }
}
