package com.hvmanker.insurance.approval.web;

public record ErrorResponse(String code) {
    public static ErrorResponse claimAlreadyDecided() {
        return new ErrorResponse("CLAIM_ALREADY_DECIDED");
    }

    public static ErrorResponse claimNotFound() {
        return new ErrorResponse("CLAIM_NOT_FOUND");
    }

    public static ErrorResponse invalidDecisionRequest() {
        return new ErrorResponse("INVALID_DECISION_REQUEST");
    }
}
