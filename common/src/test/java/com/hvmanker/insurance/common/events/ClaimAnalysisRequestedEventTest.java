package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.hvmanker.insurance.common.MoneyTestSupport.assertMoneyEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimAnalysisRequestedEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTrips() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID claimId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        ClaimAnalysisRequestedEvent original =
                new ClaimAnalysisRequestedEvent(eventId, claimId, new BigDecimal("4000.00"));
        ClaimAnalysisRequestedEvent restored =
                MAPPER.readValue(MAPPER.writeValueAsString(original), ClaimAnalysisRequestedEvent.class);
        assertEquals(eventId, restored.eventId());
        assertEquals(claimId, restored.claimId());
        assertMoneyEqual(new BigDecimal("4000.00"), restored.claimedAmount());
    }
}
