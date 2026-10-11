package com.sunil.insurance.approval.service;

import com.sunil.insurance.approval.domain.ApprovalDecision;
import com.sunil.insurance.approval.domain.ClaimApproval;
import com.sunil.insurance.approval.domain.ClaimApprovalRepository;
import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(
                approvalRepository,
                kafkaTemplate,
                new BigDecimal("10000.00")
        );
    }

    @Test
    void autoApprovesClaimsWithinTheConfiguredLimit() {
        UUID claimId = UUID.randomUUID();
        ClaimSubmittedEvent event = submittedClaim(claimId, "9000.00");
        when(approvalRepository.findById(claimId)).thenReturn(Optional.empty());

        approvalService.evaluate(event);

        ArgumentCaptor<ClaimApproval> approval = ArgumentCaptor.forClass(ClaimApproval.class);
        verify(approvalRepository).save(approval.capture());
        assertEquals(ApprovalDecision.APPROVED, approval.getValue().getDecision());
        assertEquals(new BigDecimal("9000.00"), approval.getValue().getApprovedAmount());
        verify(kafkaTemplate).send(eq(CLAIM_APPROVED), eq(claimId.toString()), any());
    }

    @Test
    void leavesHighValueClaimsForManualReview() {
        UUID claimId = UUID.randomUUID();
        ClaimSubmittedEvent event = submittedClaim(claimId, "12500.00");
        when(approvalRepository.findById(claimId)).thenReturn(Optional.empty());

        approvalService.evaluate(event);

        verify(approvalRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    private ClaimSubmittedEvent submittedClaim(UUID claimId, String amount) {
        return new ClaimSubmittedEvent(
                claimId,
                "POL-10045",
                "Test Member",
                "AUTO",
                new BigDecimal(amount),
                Instant.parse("2026-07-01T12:00:00Z")
        );
    }
}
