package com.hvmanker.insurance.approval.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InternalDecisionRequest(
        @NotNull Boolean approved,
        @NotBlank String reason,
        @NotBlank String userId) {
}
