package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hvmanker.insurance.common.outbox.SyntheticIds.AGGREGATE_A;
import static com.hvmanker.insurance.common.outbox.SyntheticIds.eventId;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void twoPublishersKeepPerAggregateOrderNoDuplicates() throws Exception {
        DataSource dataSourceOne = OutboxInboxTestSupport.newDataSource();
        DataSource dataSourceTwo = OutboxInboxTestSupport.newDataSource();

        List<Boolean> publisherOneLockOutcomes = new CopyOnWriteArrayList<>();
        List<Boolean> publisherTwoLockOutcomes = new CopyOnWriteArrayList<>();

        LockRecordingJdbcTemplate jdbcOne =
                new LockRecordingJdbcTemplate(dataSourceOne, publisherOneLockOutcomes);
        LockRecordingJdbcTemplate jdbcTwo =
                new LockRecordingJdbcTemplate(dataSourceTwo, publisherTwoLockOutcomes);
        TransactionTemplate txOne =
                new TransactionTemplate(new DataSourceTransactionManager(dataSourceOne));
        TransactionTemplate txTwo =
                new TransactionTemplate(new DataSourceTransactionManager(dataSourceTwo));

        OutboxMessageSender kafkaSender = OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30));

        AnnotationConfigApplicationContext publisherContextOne =
                publisherContext(dataSourceOne, jdbcOne);
        AnnotationConfigApplicationContext publisherContextTwo =
                publisherContext(dataSourceTwo, jdbcTwo);

        try {
            OutboxPublisher publisherOne = publisherContextOne.getBean(OutboxPublisher.class);
            OutboxPublisher publisherTwo = publisherContextTwo.getBean(OutboxPublisher.class);
            LatchBlockingOutboxMessageSender senderOne =
                    publisherContextOne.getBean(LatchBlockingOutboxMessageSender.class);
            LatchBlockingOutboxMessageSender senderTwo =
                    publisherContextTwo.getBean(LatchBlockingOutboxMessageSender.class);
            senderOne.setDelegate(kafkaSender);
            senderTwo.setDelegate(kafkaSender);

            phaseA(publisherOne, publisherTwo, senderOne, senderTwo, txOne, txTwo, publisherOneLockOutcomes, publisherTwoLockOutcomes);
            phaseB(publisherOne, publisherTwo, senderOne, senderTwo, txOne, txTwo, publisherOneLockOutcomes, publisherTwoLockOutcomes);
            phaseC(publisherOne, publisherTwo, senderOne, senderTwo, txOne, txTwo);

            try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
                List<ConsumerRecord<String, String>> records =
                        OutboxInboxTestSupport.consumeUntilQuiet(consumer, 52);
                assertEquals(52, records.size());
                for (int i = 1; i <= 52; i++) {
                    String eventIdHeader = new String(
                            records.get(i - 1)
                                    .headers()
                                    .lastHeader(OutboxMessageSender.EVENT_ID_HEADER)
                                    .value(),
                            StandardCharsets.UTF_8);
                    assertEquals(eventId(i).toString(), eventIdHeader);
                }
            }

            assertEquals(
                    52,
                    jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL", Integer.class));

            assertLockOutcomeMix(publisherOneLockOutcomes);
            assertLockOutcomeMix(publisherTwoLockOutcomes);
        } finally {
            publisherContextTwo.close();
            publisherContextOne.close();
        }
    }

    private void phaseA(
            OutboxPublisher publisherOne,
            OutboxPublisher publisherTwo,
            LatchBlockingOutboxMessageSender senderOne,
            LatchBlockingOutboxMessageSender senderTwo,
            TransactionTemplate txOne,
            TransactionTemplate txTwo,
            List<Boolean> publisherOneLockOutcomes,
            List<Boolean> publisherTwoLockOutcomes)
            throws InterruptedException {
        UUID e1 = eventId(1);
        commitRow(e1, payload(1));

        CountDownLatch l1 = new CountDownLatch(1);
        senderOne.resetSendInvocations();
        senderTwo.resetSendInvocations();
        senderOne.setBlockLatch(l1);

        int p2LocksBefore = publisherTwoLockOutcomes.size();
        Thread publisherOneThread = new Thread(
                () -> txOne.executeWithoutResult(status -> publisherOne.publishPending()), "p1-phase-a");
        publisherOneThread.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> publisherOneLockOutcomes.contains(Boolean.TRUE)
                && senderOne.hasEnteredSend());

        txTwo.executeWithoutResult(status -> publisherTwo.publishPending());

        assertEquals(1, publisherTwoLockOutcomes.size() - p2LocksBefore);
        assertFalse(publisherTwoLockOutcomes.get(publisherTwoLockOutcomes.size() - 1));
        assertEquals(0, senderTwo.sendInvocations());
        assertNull(jdbcTemplate.queryForObject("SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));

        l1.countDown();
        publisherOneThread.join();

        assertNotNull(jdbcTemplate.queryForObject("SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e1));
        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(1, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 1).size());
        }
        senderOne.clearBlockLatch();
    }

    private void phaseB(
            OutboxPublisher publisherOne,
            OutboxPublisher publisherTwo,
            LatchBlockingOutboxMessageSender senderOne,
            LatchBlockingOutboxMessageSender senderTwo,
            TransactionTemplate txOne,
            TransactionTemplate txTwo,
            List<Boolean> publisherOneLockOutcomes,
            List<Boolean> publisherTwoLockOutcomes)
            throws InterruptedException {
        UUID e2 = eventId(2);
        commitRow(e2, payload(2));

        CountDownLatch l2 = new CountDownLatch(1);
        senderOne.resetSendInvocations();
        senderTwo.resetSendInvocations();
        senderTwo.setBlockLatch(l2);

        int p1LocksBefore = publisherOneLockOutcomes.size();
        Thread publisherTwoThread = new Thread(
                () -> txTwo.executeWithoutResult(status -> publisherTwo.publishPending()), "p2-phase-b");
        publisherTwoThread.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> publisherTwoLockOutcomes.contains(Boolean.TRUE)
                && senderTwo.hasEnteredSend());

        txOne.executeWithoutResult(status -> publisherOne.publishPending());

        assertEquals(1, publisherOneLockOutcomes.size() - p1LocksBefore);
        assertFalse(publisherOneLockOutcomes.get(publisherOneLockOutcomes.size() - 1));
        assertEquals(0, senderOne.sendInvocations());
        assertNull(jdbcTemplate.queryForObject("SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e2));

        l2.countDown();
        publisherTwoThread.join();

        assertNotNull(jdbcTemplate.queryForObject("SELECT sent_at FROM outbox WHERE event_id = ?", Object.class, e2));
        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            assertEquals(2, OutboxInboxTestSupport.consumeUntilQuiet(consumer, 2).size());
        }
        senderTwo.clearBlockLatch();
    }

    private void phaseC(
            OutboxPublisher publisherOne,
            OutboxPublisher publisherTwo,
            LatchBlockingOutboxMessageSender senderOne,
            LatchBlockingOutboxMessageSender senderTwo,
            TransactionTemplate txOne,
            TransactionTemplate txTwo) {
        senderOne.clearBlockLatch();
        senderTwo.clearBlockLatch();
        senderOne.setDelegate(OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)));
        senderTwo.setDelegate(OutboxMessageSender.kafka(kafkaTemplate, Duration.ofSeconds(30)));

        for (int i = 3; i <= 52; i++) {
            commitRow(eventId(i), payload(i));
        }

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        executor.scheduleAtFixedRate(
                () -> txOne.executeWithoutResult(status -> publisherOne.publishPending()),
                0,
                50,
                TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(
                () -> txTwo.executeWithoutResult(status -> publisherTwo.publishPending()),
                0,
                50,
                TimeUnit.MILLISECONDS);

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL", Integer.class)
                == 52);

        executor.shutdownNow();

        try (KafkaConsumer<String, String> consumer = OutboxInboxTestSupport.newConsumer(topic)) {
            List<ConsumerRecord<String, String>> phaseRecords =
                    OutboxInboxTestSupport.consumeUntilQuiet(consumer, 52);
            assertEquals(52, phaseRecords.size());
            for (int i = 3; i <= 52; i++) {
                String eventIdHeader = new String(
                        phaseRecords
                                .get(i - 1)
                                .headers()
                                .lastHeader(OutboxMessageSender.EVENT_ID_HEADER)
                                .value(),
                        StandardCharsets.UTF_8);
                assertEquals(eventId(i).toString(), eventIdHeader);
            }
        }
    }

    private static AnnotationConfigApplicationContext publisherContext(
            DataSource dataSource, JdbcTemplate jdbcTemplate) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.getBeanFactory().registerSingleton("jdbcTemplate", jdbcTemplate);
        context.register(OutboxConcurrentPublisherConfig.class);
        context.refresh();
        return context;
    }

    private void commitRow(UUID eventId, String payload) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                """
                INSERT INTO outbox (event_id, aggregate_id, topic, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                eventId,
                AGGREGATE_A,
                topic,
                payload));
    }

    private static String payload(int rowNumber) {
        return "{\"claimId\":\"" + AGGREGATE_A + "\",\"n\":" + rowNumber + "}";
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
