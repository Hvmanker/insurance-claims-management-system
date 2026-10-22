package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClaimRejectedEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void newShapeRoundTrips() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID decisionId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID claimId = UUID.fromString("00000000-0000-0000-0000-000000000013");
        ClaimRejectedEvent original = new ClaimRejectedEvent(
                eventId,
                decisionId,
                claimId,
                "POL-TEST-2002",
                "test reason",
                new DecidedBy("u-test-002", "AGENT"),
                null);
        ClaimRejectedEvent restored = MAPPER.readValue(MAPPER.writeValueAsString(original), ClaimRejectedEvent.class);
        assertEquals(original, restored);
    }

    @Test
    void oldShapeStillDeserializes() throws Exception {
        String json = """
                {
                  "claimId": "33333333-3333-3333-3333-333333333333",
                  "reason": "test"
                }
                """;
        ClaimRejectedEvent event = MAPPER.readValue(json, ClaimRejectedEvent.class);
        assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), event.claimId());
        assertEquals("test", event.reason());
        assertNull(event.eventId());
        assertNull(event.decisionId());
        assertNull(event.decidedBy());
    }
}
