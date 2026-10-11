package com.hvmanker.insurance.notification.messaging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hvmanker.insurance.common.events.ClaimApprovedEvent;
import com.hvmanker.insurance.common.events.ClaimRejectedEvent;
import com.hvmanker.insurance.common.events.ClaimSettledEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_SETTLED;
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
        UUID claimId = UUID.randomUUID();
        listener.onApproved(new ClaimApprovedEvent(
                claimId,
                LEAK_POLICY,
                LEAK_AMOUNT,
                "approver@example.com",
                Instant.parse("2026-01-01T00:00:00Z")));

        assertNoSensitiveDataInLogs();
        assertExpectedLogLine(claimId, CLAIM_APPROVED);
    }

    @Test
    void onRejected_doesNotLogPolicyNumberOrReason() {
        UUID claimId = UUID.randomUUID();
        listener.onRejected(new ClaimRejectedEvent(
                claimId,
                LEAK_POLICY,
                LEAK_REASON,
                Instant.parse("2026-01-01T00:00:00Z")));

        assertNoSensitiveDataInLogs();
        assertExpectedLogLine(claimId, CLAIM_REJECTED);
    }

    @Test
    void onSettled_doesNotLogPolicyNumberOrPaidAmount() {
        UUID claimId = UUID.randomUUID();
        listener.onSettled(new ClaimSettledEvent(
                claimId,
                UUID.randomUUID(),
                LEAK_POLICY,
                LEAK_AMOUNT,
                Instant.parse("2026-01-01T00:00:00Z")));

        assertNoSensitiveDataInLogs();
        assertExpectedLogLine(claimId, CLAIM_SETTLED);
    }

    private void assertNoSensitiveDataInLogs() {
        String combined = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertThat(combined).doesNotContain(LEAK_POLICY);
        assertThat(combined).doesNotContain(LEAK_REASON);
        assertThat(combined).doesNotContain("987654.32");
    }

    private void assertExpectedLogLine(UUID claimId, String eventType) {
        assertThat(logAppender.list).hasSize(1);
        String message = logAppender.list.get(0).getFormattedMessage();
        assertThat(message).contains("claimId=" + claimId);
        assertThat(message).contains("eventType=" + eventType);
    }
}
