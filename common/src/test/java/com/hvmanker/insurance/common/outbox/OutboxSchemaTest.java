package com.hvmanker.insurance.common.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxSchemaTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws IOException {
        OutboxInboxTestSupport.startContainers();
        DataSource dataSource = OutboxInboxTestSupport.newDataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS processed_events CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox CASCADE");
        OutboxInboxTestSupport.applySchema(jdbcTemplate);
    }

    @Test
    void templateCreatesOutboxTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name, data_type, udt_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'outbox'
                ORDER BY ordinal_position
                """);

        assertColumn(columns, "id", "bigint", "NO");
        assertColumn(columns, "event_id", "uuid", "NO");
        assertColumn(columns, "aggregate_id", "uuid", "NO");
        assertColumn(columns, "topic", "text", "NO");
        assertColumn(columns, "payload", "jsonb", "NO");
        assertColumn(columns, "created_at", "timestamp with time zone", "NO");
        assertColumn(columns, "sent_at", "timestamp with time zone", "YES");

        Integer pkCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'outbox' AND constraint_type = 'PRIMARY KEY'
                """,
                Integer.class);
        assertEquals(1, pkCount);

        Integer uniqueEventId = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'outbox' AND c.contype = 'u'
                """,
                Integer.class);
        assertTrue(uniqueEventId >= 1);
    }

    @Test
    void templateCreatesProcessedEventsTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'processed_events'
                ORDER BY ordinal_position
                """);

        assertColumn(columns, "consumer_group", "text", "NO");
        assertColumn(columns, "event_id", "uuid", "NO");
        assertColumn(columns, "processed_at", "timestamp with time zone", "NO");

        List<String> pkColumns = jdbcTemplate.query(
                """
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                JOIN pg_class c ON c.oid = i.indrelid
                WHERE c.relname = 'processed_events' AND i.indisprimary
                ORDER BY a.attnum
                """,
                (rs, rowNum) -> rs.getString(1));
        assertEquals(List.of("consumer_group", "event_id"), pkColumns);
    }

    private static void assertColumn(
            List<Map<String, Object>> columns, String name, String dataType, String nullable) {
        Map<String, Object> column = columns.stream()
                .filter(entry -> name.equals(entry.get("column_name")))
                .findFirst()
                .orElseThrow();
        assertEquals(dataType, column.get("data_type"));
        assertEquals(nullable, column.get("is_nullable"));
    }
}
