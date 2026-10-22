package com.hvmanker.insurance.common.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventMdcTest {

    @Test
    void openPutsClaimAndEventIdsAndCloseClearsThem() {
        UUID claimId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        try (EventMdc ignored = EventMdc.open(claimId, eventId)) {
            assertEquals(claimId.toString(), MDC.get("claimId"));
            assertEquals(eventId.toString(), MDC.get("eventId"));
        }

        assertNull(MDC.get("claimId"));
        assertNull(MDC.get("eventId"));
    }
}
