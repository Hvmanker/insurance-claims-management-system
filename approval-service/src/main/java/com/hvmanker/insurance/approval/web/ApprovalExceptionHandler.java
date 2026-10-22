package com.hvmanker.insurance.approval.web;

import com.hvmanker.insurance.approval.service.ClaimAlreadyDecidedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApprovalExceptionHandler {
    @ExceptionHandler(ClaimAlreadyDecidedException.class)
    public ResponseEntity<ConflictErrorResponse> claimAlreadyDecided() {
        return ResponseEntity.status(409).body(ConflictErrorResponse.claimAlreadyDecided());
    }
}
