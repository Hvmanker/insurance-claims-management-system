package com.hvmanker.insurance.approval.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InternalDecisionRequest(
        @NotBlank String action,
        @NotBlank String reason,
        @NotNull @Valid DecidedByBody decidedBy,
        BigDecimal approvedAmount) {

    public record DecidedByBody(
            @NotBlank String userId,
            @NotBlank String role) {
    }
}
