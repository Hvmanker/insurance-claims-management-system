package com.hvmanker.insurance.approval.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimAlreadyDecidedException extends RuntimeException {
    public ClaimAlreadyDecidedException() {
        super("CLAIM_ALREADY_DECIDED");
    }
}
