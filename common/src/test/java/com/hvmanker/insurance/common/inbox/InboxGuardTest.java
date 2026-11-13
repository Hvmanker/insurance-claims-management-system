package com.hvmanker.insurance.common.inbox;

import com.hvmanker.insurance.common.outbox.OutboxInboxSpringHarness;
import com.hvmanker.insurance.common.outbox.OutboxInboxTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hvmanker.insurance.common.outbox.SyntheticIds.eventId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxGuardTest {

    private InboxGuard inboxGuard;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        var context = OutboxInboxSpringHarness.context();
        inboxGuard = context.getBean(InboxGuard.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        transactionTemplate = context.getBean(TransactionTemplate.class);
        OutboxInboxTestSupport.truncateTables(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS inbox_guard_marker (marker TEXT)");
        jdbcTemplate.execute("TRUNCATE inbox_guard_marker");
    }

    @Test
    void firstTimeTrueThenFalse() {
        UUID e1 = eventId(1);

        Boolean first = transactionTemplate.execute(status -> inboxGuard.firstTime("claims-approval", e1));
        Boolean second = transactionTemplate.execute(status -> inboxGuard.firstTime("claims-approval", e1));

        assertTrue(first);
        assertFalse(second);
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                        Integer.class,
                        "claims-approval",
                        e1));
    }

    @Test
    void differentConsumerGroupIsIndependent() {
        UUID e1 = eventId(1);

        assertEquals(Boolean.TRUE, transactionTemplate.execute(status -> inboxGuard.firstTime("group-a", e1)));
        assertEquals(Boolean.TRUE, transactionTemplate.execute(status -> inboxGuard.firstTime("group-b", e1)));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
    }

    @Test
    void rollbackForgetsEvent() {
        UUID e2 = eventId(2);

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            assertTrue(inboxGuard.firstTime("g", e2));
            throw new IllegalStateException("rollback");
        }));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
        assertEquals(Boolean.TRUE, transactionTemplate.execute(status -> inboxGuard.firstTime("g", e2)));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
    }

    @Test
    void concurrentFirstTimeExactlyOneWinner() throws Exception {
        UUID e3 = eventId(3);
        for (int run = 0; run < 10; run++) {
            OutboxInboxTestSupport.truncateTables(jdbcTemplate);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger trueCount = new AtomicInteger();

            try {
                Future<?>[] futures = new Future[8];
                for (int i = 0; i < 8; i++) {
                    futures[i] = pool.submit(() -> {
                        start.await();
                        TransactionTemplate perThreadTx = new TransactionTemplate(
                                OutboxInboxSpringHarness.context().getBean(org.springframework.transaction.PlatformTransactionManager.class));
                        Boolean result = perThreadTx.execute(status -> inboxGuard.firstTime("g", e3));
                        if (Boolean.TRUE.equals(result)) {
                            trueCount.incrementAndGet();
                        }
                        return null;
                    });
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, trueCount.get());
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
        }
    }

    @Test
    void noExceptionOnDuplicate() {
        UUID e4 = eventId(4);
        transactionTemplate.executeWithoutResult(status -> assertTrue(inboxGuard.firstTime("g", e4)));

        transactionTemplate.executeWithoutResult(status -> {
            assertFalse(inboxGuard.firstTime("g", e4));
            jdbcTemplate.update("INSERT INTO inbox_guard_marker (marker) VALUES (?)", "still-here");
        });

        assertEquals(
                1,
                jdbcTemplate.queryForObject("SELECT count(*) FROM inbox_guard_marker", Integer.class));
    }

}
