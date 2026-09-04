package com.sunil.insurance.claims.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SubmitClaimRequest(
        @NotBlank String policyNumber,
        @NotBlank String claimType,
        @NotBlank @Size(max = 120) String reason,
        @NotBlank @Size(max = 2000) String description,
        @NotNull @DecimalMin("1.00") BigDecimal estimatedAmount
) {
}
