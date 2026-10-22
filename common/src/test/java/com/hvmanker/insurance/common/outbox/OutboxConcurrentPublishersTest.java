package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.hvmanker.insurance.common.outbox.SyntheticIds.AGGREGATE_A;
import static com.hvmanker.insurance.common.outbox.SyntheticIds.eventId;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxConcurrentPublishersTest {

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
        topic = OutboxInboxTestSupport.freshTopicName("test.outbox.concurrent");
        OutboxInboxTestSupport.createTopic(topic, 1);
    }

    @RepeatedTest(5)
    void twoPublishersKeepPerAggregateOrderNoDuplicates() {
        for (int i = 1; i <= 50; i++) {
            UUID event = eventId(i);
            int rowNumber = i;
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                    """
                    INSERT INTO outbox (event_id, aggregate_id, topic, payload)
                    VALUES (?, ?, ?, ?::jsonb)
                    """,
                    event,
                    AGGREGATE_A,
                    topic,
                    "{\"claimId\":\"" + AGGREGATE_A + "\",\"n\":" + rowNumber + "}"));
        }

        DataSource dataSourceOne = OutboxInboxTestSupport.newDataSource();
        DataSource dataSourceTwo = OutboxInboxTestSupport.newDataSource();
        JdbcTemplate jdbcOne = new JdbcTemplate(dataSourceOne);
        JdbcTemplate jdbcTwo = new JdbcTemplate(dataSourceTwo);
        TransactionTemplate txOne = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSourceOne));
        TransactionTemplate txTwo = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSourceTwo));

        CountDownLatch releaseWhileLockHeld = new CountDownLatch(1);
        AtomicBoolean releaseTriggered = new AtomicBoolean();
        List<Boolean> publisherOneLockOutcomes = new CopyOnWriteArrayList<>();
        List<Boolean> publisherTwoLockOutcomes = new CopyOnWriteArrayList<>();

        Consumer<Boolean> releaseOnContention = acquired -> {
            if (!acquired && releaseTriggered.compareAndSet(false, true)) {
                releaseWhileLockHeld.countDown();
            }
        };

        OutboxMessageSender kafkaSender = OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30));
        OutboxMessageSender slowSender = record -> {
            if (!releaseWhileLockHeld.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for advisory lock contention");
            }
            Thread.sleep(5);
            kafkaSender.send(record);
        };

        OutboxPublisher publisherOne = new OutboxPublisher(
                jdbcOne,
                slowSender,
                OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY,
                acquired -> {
                    publisherOneLockOutcomes.add(acquired);
                    releaseOnContention.accept(acquired);
                });
        OutboxPublisher publisherTwo = new OutboxPublisher(
                jdbcTwo,
                slowSender,
                OutboxInboxTestSupport.DEFAULT_ADVISORY_LOCK_KEY,
                acquired -> {
                    publisherTwoLockOutcomes.add(acquired);
                    releaseOnContention.accept(acquired);
                });

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        executor.scheduleAtFixedRate(
                () -> txOne.executeWithoutResult(status -> publisherOne.publishPending()), 0, 50, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(
                () -> txTwo.executeWithoutResult(status -> publisherTwo.publishPending()), 0, 50, TimeUnit.MILLISECONDS);

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL", Integer.class) == 50);

        executor.shutdownNow();

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            List<ConsumerRecord<String, String>> records =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 50);
            assertEquals(50, records.size());
            List<String> consumedEventIds = new ArrayList<>();
            for (ConsumerRecord<String, String> record : records) {
                String eventIdHeader = new String(
                        record.headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER).value(),
                        StandardCharsets.UTF_8);
                consumedEventIds.add(eventIdHeader);
                assertNotNull(record.timestamp());
            }
            for (int i = 1; i <= 50; i++) {
                assertEquals(eventId(i).toString(), consumedEventIds.get(i - 1));
            }
        }

        assertEquals(50, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL", Integer.class));

        assertLockOutcomeMix(publisherOneLockOutcomes);
        assertLockOutcomeMix(publisherTwoLockOutcomes);
    }

    private static void assertLockOutcomeMix(List<Boolean> lockOutcomes) {
        assertTrue(
                lockOutcomes.stream().anyMatch(Boolean::booleanValue),
                () -> "expected at least one advisory lock acquired cycle, saw " + lockOutcomes);
        assertTrue(
                lockOutcomes.stream().anyMatch(acquired -> !acquired),
                () -> "expected at least one advisory lock contention cycle, saw " + lockOutcomes);
    }
}
