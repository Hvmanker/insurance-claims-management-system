package com.hvmanker.insurance.approval.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.approval.domain.ApprovalDecision;
import com.hvmanker.insurance.approval.domain.ApprovalSource;
import com.hvmanker.insurance.approval.domain.ClaimApproval;
import com.hvmanker.insurance.approval.domain.ClaimApprovalRepository;
import com.hvmanker.insurance.approval.domain.ClaimWorkflow;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowRepository;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowStore;
import com.hvmanker.insurance.approval.domain.WorkflowStatus;
import com.hvmanker.insurance.approval.web.InternalDecisionLookupResponse;
import com.hvmanker.insurance.approval.web.InternalDecisionRequest;
import com.hvmanker.insurance.common.events.ClaimAnalysisRequestedEvent;
import com.hvmanker.insurance.common.events.ClaimApprovedEvent;
import com.hvmanker.insurance.common.events.ClaimRejectedEvent;
import com.hvmanker.insurance.common.events.ClaimSubmittedEvent;
import com.hvmanker.insurance.common.events.DecidedBy;
import com.hvmanker.insurance.common.inbox.InboxGuard;
import com.hvmanker.insurance.common.logging.EventMdc;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ApprovalService {
    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final String CLAIM_SUBMITTED_CONSUMER_GROUP = "approval-service";
    private final ClaimApprovalRepository approvalRepository;
    private final ClaimWorkflowRepository claimWorkflowRepository;
    private final ClaimWorkflowStore claimWorkflowStore;
    private final InboxGuard inboxGuard;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final DecisionConflictRecorder decisionConflictRecorder;

    public ApprovalService(
            ClaimApprovalRepository approvalRepository,
            ClaimWorkflowRepository claimWorkflowRepository,
            ClaimWorkflowStore claimWorkflowStore,
            InboxGuard inboxGuard,
            OutboxWriter outboxWriter,
            ObjectMapper objectMapper,
            DecisionConflictRecorder decisionConflictRecorder) {
        this.approvalRepository = approvalRepository;
        this.claimWorkflowRepository = claimWorkflowRepository;
        this.claimWorkflowStore = claimWorkflowStore;
        this.inboxGuard = inboxGuard;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.decisionConflictRecorder = decisionConflictRecorder;
    }

    @Transactional
    public void handleClaimSubmitted(ClaimSubmittedEvent event, UUID eventId) {
        if (!inboxGuard.firstTime(CLAIM_SUBMITTED_CONSUMER_GROUP, eventId)) {
            EventMdc.logDuplicateSkipped(log, event.claimId(), eventId);
            return;
        }

        boolean inserted = claimWorkflowStore.insertIfAbsent(
                event.claimId(),
                WorkflowStatus.ANALYSIS_PENDING,
                event.policyNumber(),
                event.estimatedAmount());
        if (!inserted) {
            log.info("duplicate claim.submitted claimId={} eventId={}", event.claimId(), eventId);
            return;
        }

        UUID outboxEventId = UUID.randomUUID();
        ClaimAnalysisRequestedEvent analysisRequested = new ClaimAnalysisRequestedEvent(
                outboxEventId,
                event.claimId(),
                event.estimatedAmount());
        appendOutbox(outboxEventId, event.claimId(), CLAIM_ANALYSIS_REQUESTED, analysisRequested);
    }

    @Transactional
    public void decide(
            UUID claimId,
            ApprovalDecision action,
            DecidedBy decidedBy,
            BigDecimal approvedAmount,
            String reason) {
        String targetStatus = action == ApprovalDecision.APPROVED ? WorkflowStatus.APPROVED : WorkflowStatus.REJECTED;
        int updated = claimWorkflowStore.transitionStatus(
                claimId,
                targetStatus,
                List.of(WorkflowStatus.ANALYSIS_PENDING, WorkflowStatus.REVIEW_READY));
        if (updated == 0) {
            if (!claimWorkflowStore.existsByClaimId(claimId)) {
                throw new ClaimWorkflowNotFoundException();
            }
            decisionConflictRecorder.recordHumanLostConflict(
                    claimId,
                    decidedBy.userId(),
                    decidedBy.role(),
                    action);
            throw new ClaimAlreadyDecidedException();
        }

        ClaimWorkflow workflow = claimWorkflowRepository.findById(claimId).orElseThrow();
        UUID decisionId = UUID.randomUUID();
        Instant decidedAt = Instant.now();
        BigDecimal resolvedApprovedAmount = action == ApprovalDecision.APPROVED
                ? (approvedAmount != null ? approvedAmount : workflow.getClaimedAmount())
                : null;
        ClaimApproval approval = new ClaimApproval(
                claimId,
                workflow.getPolicyNumber(),
                workflow.getClaimedAmount(),
                resolvedApprovedAmount,
                action,
                reason,
                decidedAt,
                decisionId,
                decidedBy.userId(),
                decidedBy.role(),
                ApprovalSource.HUMAN);
        approvalRepository.save(approval);

        UUID outboxEventId = UUID.randomUUID();
        if (action == ApprovalDecision.APPROVED) {
            appendOutbox(
                    outboxEventId,
                    claimId,
                    CLAIM_APPROVED,
                    new ClaimApprovedEvent(
                            outboxEventId,
                            decisionId,
                            claimId,
                            workflow.getPolicyNumber(),
                            resolvedApprovedAmount,
                            workflow.getClaimedAmount(),
                            decidedBy,
                            null,
                            decidedAt));
            return;
        }
        appendOutbox(
                outboxEventId,
                claimId,
                CLAIM_REJECTED,
                new ClaimRejectedEvent(
                        outboxEventId,
                        decisionId,
                        claimId,
                        workflow.getPolicyNumber(),
                        reason,
                        decidedBy,
                        decidedAt));
    }

    @Transactional
    public void recordHumanDecision(UUID claimId, InternalDecisionRequest request) {
        ClaimWorkflow workflow = claimWorkflowRepository.findById(claimId).orElse(null);
        if (workflow != null) {
            InternalDecisionValidator.validate(request, workflow.getClaimedAmount());
        }
        ApprovalDecision action = InternalDecisionValidator.parseAction(request.action());
        decide(
                claimId,
                action,
                new DecidedBy(request.decidedBy().userId(), request.decidedBy().role()),
                request.approvedAmount(),
                request.reason());
    }

    @Transactional(readOnly = true)
    public InternalDecisionLookupResponse lookupDecision(UUID decisionId) {
        ClaimApproval approval = approvalRepository.findByDecisionId(decisionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Decision not found"));
        return new InternalDecisionLookupResponse(
                approval.getDecisionId(),
                approval.getClaimId(),
                approval.getDecision().name(),
                approval.getApprovedAmount(),
                new DecidedBy(approval.getDecidedByUserId(), approval.getDecidedByRole()));
    }

    private void appendOutbox(UUID eventId, UUID aggregateId, String topic, Object payload) {
        try {
            outboxWriter.append(eventId, aggregateId, topic, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize outbox payload for topic " + topic, exception);
        }
    }
}
