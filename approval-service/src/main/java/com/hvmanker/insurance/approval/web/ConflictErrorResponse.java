package com.hvmanker.insurance.approval.web;

public record ConflictErrorResponse(String code) {
    public static ConflictErrorResponse claimAlreadyDecided() {
        return new ConflictErrorResponse("CLAIM_ALREADY_DECIDED");
    }
}
