package com.sunil.insurance.claims.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 12, max = 72)
        @Pattern(regexp = ".*[A-Z].*", message = "Password must include an uppercase letter")
        @Pattern(regexp = ".*[a-z].*", message = "Password must include a lowercase letter")
        @Pattern(regexp = ".*[0-9].*", message = "Password must include a number")
        String password
) {
}
