package com.hvmanker.insurance.common.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Puts {@code claimId} and {@code eventId} into the SLF4J MDC for the duration of a consumer handler.
 */
public final class EventMdc implements AutoCloseable {

    private static final String CLAIM_ID_KEY = "claimId";
    private static final String EVENT_ID_KEY = "eventId";

    private EventMdc() {
    }

    public static EventMdc open(UUID claimId, UUID eventId) {
        MDC.put(CLAIM_ID_KEY, claimId.toString());
        MDC.put(EVENT_ID_KEY, eventId.toString());
        return new EventMdc();
    }

    public static void logDuplicateSkipped(Logger log, UUID claimId, UUID eventId) {
        log.info("duplicate event skipped claimId={} eventId={}", claimId, eventId);
    }

    @Override
    public void close() {
        MDC.remove(CLAIM_ID_KEY);
        MDC.remove(EVENT_ID_KEY);
    }
}
