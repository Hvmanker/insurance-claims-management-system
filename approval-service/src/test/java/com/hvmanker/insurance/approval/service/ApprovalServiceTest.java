package com.hvmanker.insurance.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.approval.domain.ClaimApprovalRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {
    @Mock
    private ClaimApprovalRepository approvalRepository;

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

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(
                approvalRepository,
                claimWorkflowStore,
                inboxGuard,
                outboxWriter,
                objectMapper,
                decisionConflictRecorder);
    }

    @Test
    void doesNotAutoApproveOnSubmitAndRequestsAnalysisInstead() throws Exception {
        UUID claimId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ClaimSubmittedEvent event = submittedClaim(claimId, "9000.00");
        when(inboxGuard.firstTime("approval-service", eventId)).thenReturn(true);
        when(claimWorkflowStore.insertIfAbsent(
                claimId,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"" + eventId + "\"}");

        approvalService.handleClaimSubmitted(event, eventId);

        verify(approvalRepository, never()).save(any());
        verify(outboxWriter).append(any(), eq(claimId), eq(CLAIM_ANALYSIS_REQUESTED), any());
    }

    @Test
    void leavesHighValueClaimsInAnalysisPendingWithoutApproval() throws Exception {
        UUID claimId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ClaimSubmittedEvent event = submittedClaim(claimId, "12500.00");
        when(inboxGuard.firstTime("approval-service", eventId)).thenReturn(true);
        when(claimWorkflowStore.insertIfAbsent(
                claimId,
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount())).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"" + eventId + "\"}");

        approvalService.handleClaimSubmitted(event, eventId);

        verify(approvalRepository, never()).save(any());
        verify(outboxWriter).append(any(), eq(claimId), eq(CLAIM_ANALYSIS_REQUESTED), any());
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
