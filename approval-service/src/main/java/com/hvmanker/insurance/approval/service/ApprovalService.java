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
import com.hvmanker.insurance.approval.domain.DecisionAudit;
import com.hvmanker.insurance.approval.domain.DecisionAuditRepository;
import com.hvmanker.insurance.approval.domain.WorkflowStatus;
import com.hvmanker.insurance.approval.web.InternalDecisionLookupResponse;
import com.hvmanker.insurance.approval.web.InternalDecisionRequest;
import com.hvmanker.insurance.common.events.ClaimAnalysisCompletedEvent;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ApprovalService {
    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final String CLAIM_SUBMITTED_CONSUMER_GROUP = "approval-service";
    private static final String ANALYSIS_COMPLETED_CONSUMER_GROUP = "approval-service-analysis-completed";
    private static final String AUTO_APPROVAL_REASON = "automatic approval";
    private static final String OUTCOME_CONFLICT = "CONFLICT";
    private static final String REASON_CLAIM_ALREADY_DECIDED = "CLAIM_ALREADY_DECIDED";

    private final ClaimApprovalRepository approvalRepository;
    private final ClaimWorkflowRepository claimWorkflowRepository;
    private final ClaimWorkflowStore claimWorkflowStore;
    private final InboxGuard inboxGuard;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final DecisionAuditRepository decisionAuditRepository;
    private final BigDecimal autoApprovalLimit;
    private final TransactionTemplate transactionTemplate;

    public ApprovalService(
            ClaimApprovalRepository approvalRepository,
            ClaimWorkflowRepository claimWorkflowRepository,
            ClaimWorkflowStore claimWorkflowStore,
            InboxGuard inboxGuard,
            OutboxWriter outboxWriter,
            ObjectMapper objectMapper,
            DecisionAuditRepository decisionAuditRepository,
            @Value("${claims.auto-approval-limit:10000.00}") BigDecimal autoApprovalLimit,
            TransactionTemplate transactionTemplate) {
        this.approvalRepository = approvalRepository;
        this.claimWorkflowRepository = claimWorkflowRepository;
        this.claimWorkflowStore = claimWorkflowStore;
        this.inboxGuard = inboxGuard;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.decisionAuditRepository = decisionAuditRepository;
        this.autoApprovalLimit = autoApprovalLimit;
        this.transactionTemplate = transactionTemplate;
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
    public void handleAnalysisCompleted(ClaimAnalysisCompletedEvent event) {
        UUID eventId = event.eventId();
        if (!inboxGuard.firstTime(ANALYSIS_COMPLETED_CONSUMER_GROUP, eventId)) {
            EventMdc.logDuplicateSkipped(log, event.claimId(), eventId);
            return;
        }

        ClaimWorkflow workflow = claimWorkflowRepository.findById(event.claimId()).orElse(null);
        if (workflow == null) {
            log.warn("analysis.completed for missing workflow claimId={} eventId={}", event.claimId(), eventId);
            return;
        }

        boolean autoEligible = workflow.getClaimedAmount().compareTo(autoApprovalLimit) <= 0
                && event.flags().isEmpty();
        if (autoEligible) {
            int updated = claimWorkflowStore.transitionFromAnalysisPending(
                    event.claimId(),
                    WorkflowStatus.APPROVED);
            if (updated == 0) {
                logLateAnalysisIgnored(event.claimId(), eventId, workflow.getStatus());
                recordConflict(
                        event.claimId(),
                        DecidedBy.AUTO_APPROVAL.userId(),
                        DecidedBy.AUTO_APPROVAL.role(),
                        ApprovalDecision.APPROVED);
                return;
            }
            persistAutoApproval(workflow);
            return;
        }

        int updated = claimWorkflowStore.transitionFromAnalysisPending(
                event.claimId(),
                WorkflowStatus.REVIEW_READY);
        if (updated == 0) {
            logLateAnalysisIgnored(event.claimId(), eventId, workflow.getStatus());
        }
    }

    public DecisionResult recordHumanDecision(UUID claimId, InternalDecisionRequest request) {
        ApprovalDecision action = InternalDecisionValidator.validate(request);
        ClaimWorkflow workflow = claimWorkflowRepository.findById(claimId).orElse(null);
        if (workflow != null) {
            InternalDecisionValidator.validateApprovedAmountWithinClaim(
                    request, workflow.getClaimedAmount(), action);
        }
        return transactionTemplate.execute(status -> decide(
                claimId,
                action,
                new DecidedBy(request.decidedBy().userId(), request.decidedBy().role()),
                request.approvedAmount(),
                request.reason()));
    }

    public void handleManualClaimDecision(
            UUID claimId,
            ApprovalDecision action,
            DecidedBy decidedBy,
            String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            DecisionResult result = decide(claimId, action, decidedBy, null, reason);
            if (result == DecisionResult.CONFLICT) {
                return;
            }
            if (result == DecisionResult.NOT_FOUND) {
                throw new IllegalStateException("manual decision for missing claim " + claimId);
            }
        });
    }

    public DecisionResult decide(
            UUID claimId,
            ApprovalDecision action,
            DecidedBy decidedBy,
            BigDecimal approvedAmount,
            String reason) {
        String targetStatus = action == ApprovalDecision.APPROVED ? WorkflowStatus.APPROVED : WorkflowStatus.REJECTED;
        int updated = claimWorkflowStore.transitionDecidedStatus(claimId, targetStatus);
        if (updated == 0) {
            if (!claimWorkflowRepository.existsById(claimId)) {
                return DecisionResult.NOT_FOUND;
            }
            recordConflict(claimId, decidedBy.userId(), decidedBy.role(), action);
            return DecisionResult.CONFLICT;
        }

        ClaimWorkflow workflow = claimWorkflowRepository.findById(claimId).orElseThrow();
        writeDecision(
                workflow,
                action,
                decidedBy,
                approvedAmount,
                reason,
                ApprovalSource.HUMAN);
        return DecisionResult.DECIDED;
    }

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

    private void persistAutoApproval(ClaimWorkflow workflow) {
        writeDecision(
                workflow,
                ApprovalDecision.APPROVED,
                DecidedBy.AUTO_APPROVAL,
                workflow.getClaimedAmount(),
                AUTO_APPROVAL_REASON,
                ApprovalSource.AUTO);
    }

    private void writeDecision(
            ClaimWorkflow workflow,
            ApprovalDecision action,
            DecidedBy decidedBy,
            BigDecimal approvedAmount,
            String reason,
            ApprovalSource source) {
        UUID claimId = workflow.getClaimId();
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
                source);
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

    private void logLateAnalysisIgnored(UUID claimId, UUID eventId, String status) {
        log.info("late analysis ignored claimId={} eventId={} status={}", claimId, eventId, status);
    }

    private void recordConflict(
            UUID claimId, String actorUserId, String actorRole, ApprovalDecision attemptedDecision) {
        decisionAuditRepository.save(new DecisionAudit(
                UUID.randomUUID(),
                claimId,
                actorUserId,
                actorRole,
                attemptedDecision.name(),
                OUTCOME_CONFLICT,
                REASON_CLAIM_ALREADY_DECIDED,
                Instant.now()));
    }

    private void appendOutbox(UUID eventId, UUID aggregateId, String topic, Object payload) {
        try {
            outboxWriter.append(eventId, aggregateId, topic, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize outbox payload for topic " + topic, exception);
        }
    }
}
