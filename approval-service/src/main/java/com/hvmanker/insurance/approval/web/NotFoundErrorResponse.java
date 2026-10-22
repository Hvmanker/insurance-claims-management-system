package com.hvmanker.insurance.approval.web;

public record NotFoundErrorResponse(String code) {
    public static NotFoundErrorResponse claimNotFound() {
        return new NotFoundErrorResponse("CLAIM_NOT_FOUND");
    }
}
