package com.hvmanker.insurance.approval.web;

import com.hvmanker.insurance.approval.service.ClaimAlreadyDecidedException;
import com.hvmanker.insurance.approval.service.ClaimWorkflowNotFoundException;
import com.hvmanker.insurance.approval.service.InvalidDecisionRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApprovalExceptionHandler {
    @ExceptionHandler(ClaimAlreadyDecidedException.class)
    public ResponseEntity<ConflictErrorResponse> claimAlreadyDecided() {
        return ResponseEntity.status(409).body(ConflictErrorResponse.claimAlreadyDecided());
    }

    @ExceptionHandler(ClaimWorkflowNotFoundException.class)
    public ResponseEntity<NotFoundErrorResponse> claimWorkflowNotFound() {
        return ResponseEntity.status(404).body(NotFoundErrorResponse.claimNotFound());
    }

    @ExceptionHandler(InvalidDecisionRequestException.class)
    public ResponseEntity<ValidationErrorResponse> invalidDecisionRequest() {
        return ResponseEntity.status(400).body(ValidationErrorResponse.invalidDecisionRequest());
    }
}
