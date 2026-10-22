package com.hvmanker.insurance.approval.web;

public record ValidationErrorResponse(String code) {
    public static ValidationErrorResponse invalidDecisionRequest() {
        return new ValidationErrorResponse("INVALID_DECISION_REQUEST");
    }
}
