package com.hvmanker.insurance.claims.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hvmanker.insurance.claims.domain.UserRole;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
record PersonaFixture(UUID id, String email, String fullName, UserRole role) {
}
