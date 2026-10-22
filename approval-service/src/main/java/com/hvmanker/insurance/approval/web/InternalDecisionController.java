package com.hvmanker.insurance.approval.web;

import com.hvmanker.insurance.approval.service.ApprovalService;
import com.hvmanker.insurance.approval.service.DecisionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal")
public class InternalDecisionController {
    private final ApprovalService approvalService;

    public InternalDecisionController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/claims/{claimId}/decisions")
    public ResponseEntity<?> recordDecision(
            @PathVariable UUID claimId,
            @RequestBody InternalDecisionRequest request) {
        DecisionResult result = approvalService.recordHumanDecision(claimId, request);
        return switch (result) {
            case DECIDED -> ResponseEntity.ok().build();
            case CONFLICT -> ResponseEntity.status(409).body(ErrorResponse.claimAlreadyDecided());
            case NOT_FOUND -> ResponseEntity.status(404).body(ErrorResponse.claimNotFound());
        };
    }

    @GetMapping("/decisions/{decisionId}")
    public InternalDecisionLookupResponse lookupDecision(@PathVariable UUID decisionId) {
        return approvalService.lookupDecision(decisionId);
    }
}
