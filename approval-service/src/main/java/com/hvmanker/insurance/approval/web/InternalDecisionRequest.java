package com.hvmanker.insurance.approval.web;

import java.math.BigDecimal;

public record InternalDecisionRequest(
        String action,
        String reason,
        DecidedByBody decidedBy,
        BigDecimal approvedAmount) {

    public record DecidedByBody(String userId, String role) {
    }
}
