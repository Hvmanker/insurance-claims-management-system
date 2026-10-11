package com.sunil.insurance.notification.messaging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.common.events.ClaimRejectedEvent;
import com.sunil.insurance.common.events.ClaimSettledEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNotificationListenerLogLeakTest {

    private static final String LEAK_POLICY = "POL-LEAK-9999";
    private static final String LEAK_REASON = "REASON-LEAK-SECRET";
    private static final BigDecimal LEAK_AMOUNT = new BigDecimal("987654.32");

    private ListAppender<ILoggingEvent> logAppender;
    private WorkflowNotificationListener listener;

    @BeforeEach
    void setUp() {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowNotificationListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        listener = new WorkflowNotificationListener();
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowNotificationListener.class);
        logger.detachAppender(logAppender);
    }

    @Test
    void onApproved_doesNotLogPolicyNumberOrAmount() {
        listener.onApproved(new ClaimApprovedEvent(
                UUID.randomUUID(),
                LEAK_POLICY,
                LEAK_AMOUNT,
                "approver@example.com",
                Instant.parse("2026-01-01T00:00:00Z")));

        assertNoSensitiveDataInLogs();
    }

    @Test
    void onRejected_doesNotLogPolicyNumberOrReason() {
        listener.onRejected(new ClaimRejectedEvent(
                UUID.randomUUID(),
                LEAK_POLICY,
                LEAK_REASON,
                Instant.parse("2026-01-01T00:00:00Z")));

        assertNoSensitiveDataInLogs();
    }

    @Test
    void onSettled_doesNotLogPolicyNumberOrPaidAmount() {
        listener.onSettled(new ClaimSettledEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LEAK_POLICY,
                LEAK_AMOUNT,
                Instant.parse("2026-01-01T00:00:00Z")));

        assertNoSensitiveDataInLogs();
    }

    private void assertNoSensitiveDataInLogs() {
        String combined = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertThat(combined).doesNotContain(LEAK_POLICY);
        assertThat(combined).doesNotContain(LEAK_REASON);
        assertThat(combined).doesNotContain("987654.32");
    }
}
