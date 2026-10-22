package com.hvmanker.insurance.approval.web;

import com.hvmanker.insurance.approval.domain.ClaimWorkflow;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowRepository;
import com.hvmanker.insurance.approval.service.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/internal")
public class InternalDecisionController {
    private final ApprovalService approvalService;
    private final ClaimWorkflowRepository claimWorkflowRepository;

    public InternalDecisionController(ApprovalService approvalService, ClaimWorkflowRepository claimWorkflowRepository) {
        this.approvalService = approvalService;
        this.claimWorkflowRepository = claimWorkflowRepository;
    }

    @PostMapping("/claims/{claimId}/decisions")
    public ResponseEntity<Void> recordDecision(
            @PathVariable UUID claimId,
            @Valid @RequestBody InternalDecisionRequest request) {
        ClaimWorkflow workflow = claimWorkflowRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim workflow not found"));
        approvalService.recordHumanDecision(claimId, request, workflow);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/decisions/{decisionId}")
    public InternalDecisionLookupResponse lookupDecision(@PathVariable UUID decisionId) {
        return approvalService.lookupDecision(decisionId);
    }
}
