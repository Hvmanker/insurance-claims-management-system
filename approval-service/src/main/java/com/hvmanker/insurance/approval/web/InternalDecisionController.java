package com.hvmanker.insurance.approval.web;

import com.hvmanker.insurance.approval.service.ApprovalService;
import jakarta.validation.Valid;
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
    public ResponseEntity<Void> recordDecision(
            @PathVariable UUID claimId,
            @Valid @RequestBody InternalDecisionRequest request) {
        approvalService.recordHumanDecision(claimId, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/decisions/{decisionId}")
    public InternalDecisionLookupResponse lookupDecision(@PathVariable UUID decisionId) {
        return approvalService.lookupDecision(decisionId);
    }
}
