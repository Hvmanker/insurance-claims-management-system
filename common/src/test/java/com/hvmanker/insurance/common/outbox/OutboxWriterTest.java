package com.hvmanker.insurance.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.hvmanker.insurance.common.outbox.SyntheticIds.AGGREGATE_A;
import static com.hvmanker.insurance.common.outbox.SyntheticIds.eventId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdbcTemplate jdbcTemplate;
    private OutboxWriter outboxWriter;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        var context = OutboxInboxSpringHarness.context();
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        outboxWriter = context.getBean(OutboxWriter.class);
        transactionTemplate = context.getBean(TransactionTemplate.class);
        OutboxInboxTestSupport.truncateTables(jdbcTemplate);
    }

    @Test
    void appendWithoutTransactionThrows() {
        UUID e1 = eventId(1);
        String payload = payloadForAggregate(AGGREGATE_A);

        assertThrows(IllegalTransactionStateException.class, () -> outboxWriter.append(
                e1, AGGREGATE_A, "test.outbox", payload));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    }

    @Test
    void appendInsideCommittedTransactionWritesOneRow() throws Exception {
        UUID e1 = eventId(1);
        String payload = payloadForAggregate(AGGREGATE_A);

        transactionTemplate.executeWithoutResult(status -> outboxWriter.append(
                e1, AGGREGATE_A, "test.outbox", payload));

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Integer.class));
        var row = jdbcTemplate.queryForMap(
                "SELECT event_id, aggregate_id, topic, payload::text AS payload, sent_at, created_at FROM outbox");
        assertEquals(e1, row.get("event_id"));
        assertEquals(AGGREGATE_A, row.get("aggregate_id"));
        assertEquals("test.outbox", row.get("topic"));
        JsonNode expected = JSON.readTree(payload);
        JsonNode actual = JSON.readTree((String) row.get("payload"));
        assertEquals(expected, actual);
        assertNull(row.get("sent_at"));
        assertNotNull(row.get("created_at"));
    }

    @Test
    void appendRolledBackLeavesNoRow() {
        UUID e1 = eventId(1);
        String payload = payloadForAggregate(AGGREGATE_A);

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            outboxWriter.append(e1, AGGREGATE_A, "test.outbox", payload);
            throw new IllegalStateException("rollback");
        }));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    }

    @Test
    void duplicateEventIdRejected() {
        UUID e1 = eventId(1);
        String payload = payloadForAggregate(AGGREGATE_A);

        transactionTemplate.executeWithoutResult(status -> outboxWriter.append(
                e1, AGGREGATE_A, "test.outbox", payload));

        Exception duplicate = assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(status ->
                outboxWriter.append(e1, AGGREGATE_A, "test.outbox", payload)));
        assertTrue(duplicate instanceof DuplicateKeyException
                || duplicate instanceof DataIntegrityViolationException
                || duplicate.getCause() instanceof DuplicateKeyException);

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    }

    private static String payloadForAggregate(UUID aggregateId) {
        return "{\"claimId\":\"" + aggregateId + "\",\"amount\":\"4000.00\"}";
    }
}
