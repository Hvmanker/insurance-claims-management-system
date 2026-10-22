package com.hvmanker.insurance.approval.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.approval.domain.ClaimApprovalRepository;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowRepository;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowStore;
import com.hvmanker.insurance.approval.domain.WorkflowStatus;
import com.hvmanker.insurance.common.events.ClaimSubmittedEvent;
import com.hvmanker.insurance.common.inbox.InboxGuard;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {
    private static final UUID CLAIM_331 = UUID.fromString("00000000-0000-0000-0000-000000000331");
    private static final UUID EVENT_E1 = UUID.fromString("00000000-0000-0000-0000-0000000003e1");
    private static final UUID EVENT_E2 = UUID.fromString("00000000-0000-0000-0000-0000000003e2");

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

    private ApprovalService approvalService;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(
                approvalRepository,
                claimWorkflowRepository,
                claimWorkflowStore,
                inboxGuard,
                outboxWriter,
                objectMapper,
                decisionConflictRecorder);
        Logger logger = (Logger) LoggerFactory.getLogger(ApprovalService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        logger.setLevel(Level.INFO);
    }

    @Test
    void doesNotApproveOnSubmitWithinTheLimit() throws Exception {
        UUID eventId = UUID.randomUUID();
        ClaimSubmittedEvent event = submittedClaim(UUID.randomUUID(), "9000.00");
        when(inboxGuard.firstTime("approval-service", eventId)).thenReturn(true);
        when(claimWorkflowStore.insertIfAbsent(
                event.claimId(),
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"" + eventId + "\"}");

        approvalService.handleClaimSubmitted(event, eventId);

        verify(approvalRepository, never()).save(any());
        verify(outboxWriter).append(any(), eq(event.claimId()), eq(CLAIM_ANALYSIS_REQUESTED), any());
        verify(outboxWriter, never()).append(any(), any(), eq(CLAIM_APPROVED), any());
        verify(outboxWriter, never()).append(any(), any(), eq(CLAIM_REJECTED), any());
    }

    @Test
    void doesNotApproveOnSubmitAboveTheLimit() throws Exception {
        UUID eventId = UUID.randomUUID();
        ClaimSubmittedEvent event = submittedClaim(UUID.randomUUID(), "12500.00");
        when(inboxGuard.firstTime("approval-service", eventId)).thenReturn(true);
        when(claimWorkflowStore.insertIfAbsent(
                event.claimId(),
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"" + eventId + "\"}");

        approvalService.handleClaimSubmitted(event, eventId);

        verify(approvalRepository, never()).save(any());
        verify(outboxWriter).append(any(), eq(event.claimId()), eq(CLAIM_ANALYSIS_REQUESTED), any());
        verify(outboxWriter, never()).append(any(), any(), eq(CLAIM_APPROVED), any());
        verify(outboxWriter, never()).append(any(), any(), eq(CLAIM_REJECTED), any());
    }

    @Test
    void replayedSameEventIdAddsNoRowNoEventAndLogsDuplicate() throws Exception {
        ClaimSubmittedEvent event = submittedClaim(CLAIM_331, "4000.00");
        when(inboxGuard.firstTime("approval-service", EVENT_E1)).thenReturn(true, false);
        when(claimWorkflowStore.insertIfAbsent(
                CLAIM_331,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"" + EVENT_E1 + "\"}");

        approvalService.handleClaimSubmitted(event, EVENT_E1);
        approvalService.handleClaimSubmitted(event, EVENT_E1);

        verify(claimWorkflowStore).insertIfAbsent(
                CLAIM_331,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount());
        verify(outboxWriter).append(any(), eq(CLAIM_331), eq(CLAIM_ANALYSIS_REQUESTED), any());
        verify(approvalRepository, never()).save(any());
        assertEquals(
                1,
                logAppender.list.stream()
                        .filter(eventLog -> eventLog.getLevel() == Level.INFO)
                        .count());
        assertEquals(
                "duplicate event skipped claimId=00000000-0000-0000-0000-000000000331 eventId=00000000-0000-0000-0000-0000000003e1",
                logAppender.list.get(logAppender.list.size() - 1).getFormattedMessage());
    }

    @Test
    void newEventIdSameClaimAddsNoRowNoEventAndLogsDuplicate() throws Exception {
        ClaimSubmittedEvent event = submittedClaim(CLAIM_331, "4000.00");
        when(inboxGuard.firstTime("approval-service", EVENT_E1)).thenReturn(true);
        when(inboxGuard.firstTime("approval-service", EVENT_E2)).thenReturn(true);
        when(claimWorkflowStore.insertIfAbsent(
                CLAIM_331,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true, false);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"" + EVENT_E1 + "\"}");

        approvalService.handleClaimSubmitted(event, EVENT_E1);
        approvalService.handleClaimSubmitted(event, EVENT_E2);

        verify(outboxWriter).append(any(), eq(CLAIM_331), eq(CLAIM_ANALYSIS_REQUESTED), any());
        verify(approvalRepository, never()).save(any());
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(eventLog -> eventLog.getLevel() == Level.INFO)
                .toList();
        assertEquals(1, infoEvents.size());
        assertEquals(
                "duplicate claim.submitted claimId=00000000-0000-0000-0000-000000000331 eventId=00000000-0000-0000-0000-0000000003e2",
                infoEvents.get(0).getFormattedMessage());
    }

    private ClaimSubmittedEvent submittedClaim(UUID claimId, String amount) {
        return new ClaimSubmittedEvent(
                claimId,
                "POL-10045",
                "Test Member",
                "AUTO",
                new BigDecimal(amount),
                Instant.parse("2026-07-01T12:00:00Z"));
    }
}
