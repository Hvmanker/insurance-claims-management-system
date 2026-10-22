package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimAnalysisCompletedEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTripsFlags() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000031");
        UUID claimId = UUID.fromString("00000000-0000-0000-0000-000000000032");
        ClaimAnalysisCompletedEvent withFlag = new ClaimAnalysisCompletedEvent(
                eventId, claimId, List.of("DUPLICATE_SUSPECT"));
        ClaimAnalysisCompletedEvent restoredWithFlag =
                MAPPER.readValue(MAPPER.writeValueAsString(withFlag), ClaimAnalysisCompletedEvent.class);
        assertEquals(List.of("DUPLICATE_SUSPECT"), restoredWithFlag.flags());

        ClaimAnalysisCompletedEvent emptyFlags = new ClaimAnalysisCompletedEvent(eventId, claimId, List.of());
        ClaimAnalysisCompletedEvent restoredEmpty =
                MAPPER.readValue(MAPPER.writeValueAsString(emptyFlags), ClaimAnalysisCompletedEvent.class);
        assertTrue(restoredEmpty.flags().isEmpty());

        ClaimAnalysisCompletedEvent nullFlags =
                MAPPER.readValue("{\"eventId\":\"00000000-0000-0000-0000-000000000031\","
                        + "\"claimId\":\"00000000-0000-0000-0000-000000000032\"}", ClaimAnalysisCompletedEvent.class);
        assertTrue(nullFlags.flags().isEmpty());
        assertEquals(List.of(), nullFlags.flags());
    }
}
