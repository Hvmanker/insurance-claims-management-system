package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.hvmanker.insurance.common.MoneyTestSupport.assertMoneyEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimApprovedEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void newShapeRoundTrips() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID decisionId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        UUID claimId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        ClaimApprovedEvent original = new ClaimApprovedEvent(
                eventId,
                decisionId,
                claimId,
                "POL-TEST-1001",
                new BigDecimal("15000.00"),
                new BigDecimal("15000.00"),
                new DecidedBy("u-test-001", "AGENT"),
                null,
                Instant.parse("2026-01-01T00:00:00Z"));
        ClaimApprovedEvent restored = MAPPER.readValue(MAPPER.writeValueAsString(original), ClaimApprovedEvent.class);
        assertEquals(eventId, restored.eventId());
        assertEquals(decisionId, restored.decisionId());
        assertEquals(claimId, restored.claimId());
        assertMoneyEqual(original.approvedAmount(), restored.approvedAmount());
        assertMoneyEqual(original.claimedAmount(), restored.claimedAmount());
        assertEquals(original.decidedBy(), restored.decidedBy());
    }

    @Test
    void oldShapeStillDeserializes() throws Exception {
        String json = """
                {
                  "claimId": "22222222-2222-2222-2222-222222222222",
                  "approvedAmount": 4000.00,
                  "approver": "Morgan Rivera"
                }
                """;
        ClaimApprovedEvent event = MAPPER.readValue(json, ClaimApprovedEvent.class);
        assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), event.claimId());
        assertMoneyEqual(new BigDecimal("4000.00"), event.approvedAmount());
        assertEquals("Morgan Rivera", event.approver());
        assertNull(event.eventId());
        assertNull(event.decisionId());
        assertNull(event.decidedBy());
        assertNull(event.claimedAmount());
    }

    @Test
    void approverIsDeprecated() throws Exception {
        Method approver = ClaimApprovedEvent.class.getDeclaredMethod("approver");
        assertTrue(approver.isAnnotationPresent(Deprecated.class));
    }

    @Test
    void unknownFieldIgnored() throws Exception {
        String json = """
                {
                  "claimId": "22222222-2222-2222-2222-222222222222",
                  "approvedAmount": 4000.00,
                  "approver": "Morgan Rivera",
                  "foo": 1
                }
                """;
        ClaimApprovedEvent event = MAPPER.readValue(json, ClaimApprovedEvent.class);
        assertNotNull(event.claimId());
    }
}
