package com.hvmanker.insurance.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventMdcTest {

    private static final UUID CLAIM_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID EVENT_E1 = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(EventMdcTest.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        MDC.clear();
    }

    @Test
    void consumerLogLineCarriesClaimIdAndEventId() {
        try (EventMdc ignored = EventMdc.open(CLAIM_A, EVENT_E1)) {
            logger.info("consumer handled event");
        }

        ILoggingEvent event = listAppender.list.get(0);
        assertEquals(CLAIM_A.toString(), event.getMDCPropertyMap().get("claimId"));
        assertEquals(EVENT_E1.toString(), event.getMDCPropertyMap().get("eventId"));
    }

    @Test
    void mdcClearedAfterScope() {
        try (EventMdc ignored = EventMdc.open(CLAIM_A, EVENT_E1)) {
            // scope active
        }
        assertNull(MDC.get("claimId"));
        assertNull(MDC.get("eventId"));
    }

    @Test
    void mdcClearedWhenHandlerThrows() {
        assertThrows(IllegalStateException.class, () -> {
            try (EventMdc ignored = EventMdc.open(CLAIM_A, EVENT_E1)) {
                throw new IllegalStateException("handler failed");
            }
        });
        assertNull(MDC.get("claimId"));
        assertNull(MDC.get("eventId"));
    }

    @Test
    void duplicateSkipLineFormat() {
        EventMdc.logDuplicateSkipped(logger, CLAIM_A, EVENT_E1);
        assertEquals(
                "duplicate event skipped claimId=00000000-0000-0000-0000-0000000000a1 eventId=00000000-0000-0000-0000-000000000101",
                listAppender.list.get(0).getFormattedMessage());
    }
}
