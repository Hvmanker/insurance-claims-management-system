package com.hvmanker.insurance.approval.service;

import com.hvmanker.insurance.approval.domain.ApprovalDecision;
import com.hvmanker.insurance.approval.web.InternalDecisionRequest;

import java.math.BigDecimal;

public final class InternalDecisionValidator {
    private static final String AGENT_ROLE = "AGENT";
    private static final int MAX_REASON_LENGTH = 500;

    private InternalDecisionValidator() {
    }

    public static ApprovalDecision validate(InternalDecisionRequest request) {
        if (request == null || request.decidedBy() == null) {
            throw new InvalidDecisionRequestException();
        }
        ApprovalDecision action = parseAction(request.action());
        String reason = request.reason();
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new InvalidDecisionRequestException();
        }
        if (!AGENT_ROLE.equals(request.decidedBy().role())) {
            throw new InvalidDecisionRequestException();
        }
        if (request.decidedBy().userId() == null || request.decidedBy().userId().isBlank()) {
            throw new InvalidDecisionRequestException();
        }
        BigDecimal approvedAmount = request.approvedAmount();
        if (action == ApprovalDecision.REJECTED) {
            if (approvedAmount != null) {
                throw new InvalidDecisionRequestException();
            }
            return action;
        }
        if (approvedAmount == null) {
            return action;
        }
        if (approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidDecisionRequestException();
        }
        if (approvedAmount.scale() > 2) {
            throw new InvalidDecisionRequestException();
        }
        return action;
    }

    public static void validateApprovedAmountWithinClaim(
            InternalDecisionRequest request, BigDecimal claimedAmount, ApprovalDecision action) {
        if (action != ApprovalDecision.APPROVED) {
            return;
        }
        BigDecimal approvedAmount = request.approvedAmount();
        if (approvedAmount == null) {
            return;
        }
        if (approvedAmount.compareTo(claimedAmount) > 0) {
            throw new InvalidDecisionRequestException();
        }
    }

    static ApprovalDecision parseAction(String action) {
        if (action == null) {
            throw new InvalidDecisionRequestException();
        }
        try {
            return ApprovalDecision.valueOf(action);
        } catch (IllegalArgumentException exception) {
            throw new InvalidDecisionRequestException();
        }
    }
}
