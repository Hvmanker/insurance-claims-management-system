package com.hvmanker.insurance.approval.web;

import com.hvmanker.insurance.approval.service.InvalidDecisionRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApprovalExceptionHandler {
    @ExceptionHandler(InvalidDecisionRequestException.class)
    public ResponseEntity<ErrorResponse> invalidDecisionRequest() {
        return ResponseEntity.status(400).body(ErrorResponse.invalidDecisionRequest());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableMessage() {
        return ResponseEntity.status(400).body(ErrorResponse.invalidDecisionRequest());
    }
}
