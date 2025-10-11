package com.hvmanker.insurance.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalPasswordValidatorTest {

    @Test
    void acceptsStrongPassword() {
        assertTrue(PortalPasswordValidator.isValid("UniqueAgentPasswordForTests-7x"));
    }

    @Test
    void rejectsShortPasswordWithExpectedMessages() {
        List<String> messages = PortalPasswordValidator.violationMessages("Ab1");
        assertTrue(messages.stream().anyMatch(m -> m.contains("between 12 and 72")));
    }
}
