package com.sunil.insurance.claims.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualDecisionRequest(
        @NotNull Boolean approved,
        @NotBlank @Size(max = 500) String reason
) {
}
