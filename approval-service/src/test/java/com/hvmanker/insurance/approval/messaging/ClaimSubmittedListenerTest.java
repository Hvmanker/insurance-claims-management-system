package com.hvmanker.insurance.approval.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.approval.domain.ClaimApprovalRepository;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowRepository;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowStore;
import com.hvmanker.insurance.approval.domain.WorkflowStatus;
import com.hvmanker.insurance.approval.service.ApprovalService;
import com.hvmanker.insurance.approval.service.DecisionConflictRecorder;
import com.hvmanker.insurance.common.events.ClaimSubmittedEvent;
import com.hvmanker.insurance.common.inbox.InboxGuard;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_SUBMITTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimSubmittedListenerTest {
    private static final UUID CLAIM_332 = UUID.fromString("00000000-0000-0000-0000-000000000332");
    private static final UUID DERIVED_EVENT_ID =
            UUID.fromString("a5e2f3d3-e906-3bcf-b263-9d41ba92fe03");

    @Mock
    private ClaimApprovalRepository approvalRepository;

    @Mock
    private ClaimWorkflowRepository claimWorkflowRepository;

    @Mock
    private ClaimWorkflowStore claimWorkflowStore;

    @Mock
    private InboxGuard inboxGuard;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DecisionConflictRecorder decisionConflictRecorder;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ClaimSubmittedListener listener;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
        ApprovalService approvalService = new ApprovalService(
                approvalRepository,
                claimWorkflowRepository,
                claimWorkflowStore,
                inboxGuard,
                outboxWriter,
                objectMapper,
                decisionConflictRecorder,
                transactionTemplate);
        listener = new ClaimSubmittedListener(approvalService);

        Logger logger = (Logger) LoggerFactory.getLogger(ClaimSubmittedListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        logger.setLevel(Level.WARN);

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test
    void missingEventIdHeaderUsesDerivedKey() {
        ClaimSubmittedEvent event = new ClaimSubmittedEvent(
                CLAIM_332,
                "POL-1A3-TEST",
                "Test Member",
                "AUTO",
                new BigDecimal("4000.00"),
                Instant.parse("2026-07-01T12:00:00Z"));
        ConsumerRecord<String, ClaimSubmittedEvent> record =
                new ConsumerRecord<>(CLAIM_SUBMITTED, 0, 0L, CLAIM_332.toString(), event);

        when(inboxGuard.firstTime("approval-service", DERIVED_EVENT_ID)).thenReturn(true);
        when(claimWorkflowStore.insertIfAbsent(
                CLAIM_332,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true);

        listener.onClaimSubmitted(record);

        UUID expectedDerived = UUID.nameUUIDFromBytes(
                ("claim.submitted:" + CLAIM_332).getBytes(StandardCharsets.UTF_8));
        assertEquals(DERIVED_EVENT_ID, expectedDerived);
        verify(inboxGuard).firstTime("approval-service", DERIVED_EVENT_ID);
        verify(claimWorkflowStore).insertIfAbsent(
                CLAIM_332,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount());
        verify(outboxWriter).append(any(), eq(CLAIM_332), eq(CLAIM_ANALYSIS_REQUESTED), any());
        assertEquals(1, logAppender.list.size());
        assertEquals(
                "claim.submitted without eventId claimId=00000000-0000-0000-0000-000000000332",
                logAppender.list.get(0).getFormattedMessage());
    }
}
