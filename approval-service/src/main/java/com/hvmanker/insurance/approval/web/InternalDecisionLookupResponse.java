package com.hvmanker.insurance.approval.web;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.hvmanker.insurance.common.events.DecidedBy;

import java.math.BigDecimal;
import java.util.UUID;

@JsonPropertyOrder({"decisionId", "claimId", "action", "approvedAmount", "decidedBy"})
public record InternalDecisionLookupResponse(
        UUID decisionId,
        UUID claimId,
        String action,
        BigDecimal approvedAmount,
        DecidedBy decidedBy) {
}
