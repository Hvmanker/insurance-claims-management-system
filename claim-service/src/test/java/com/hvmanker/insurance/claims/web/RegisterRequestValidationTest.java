package com.hvmanker.insurance.claims.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsStrongRegistrationPassword() {
        var request = new RegisterRequest("Ada Lovelace", "ada@insureflow.local", "ValidPass12345");
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsWeakPasswordWithRegistrationMessages() {
        var request = new RegisterRequest("Ada Lovelace", "ada@insureflow.local", "AB1");
        Set<String> messages = validator.validate(request).stream()
                .map(v -> v.getMessage())
                .collect(Collectors.toSet());
        assertTrue(messages.contains("size must be between 12 and 72"));
        assertTrue(messages.contains("Password must include a lowercase letter"));
        assertEquals(2, messages.size());
    }
}
