package com.hvmanker.insurance.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
    void rejectsPasswordShorterThanMinimumLength() {
        List<String> messages = PortalPasswordValidator.violationMessages("Ab1cdEFgh");
        assertEquals(List.of("size must be between 12 and 72"), messages);
    }

    @Test
    void rejectsPasswordLongerThanMaximumUtf8ByteLength() {
        String tooLong = "A1a" + "x".repeat(PortalPasswordValidator.MAX_LENGTH);
        List<String> messages = PortalPasswordValidator.violationMessages(tooLong);
        assertEquals(List.of("size must be between 12 and 72"), messages);
    }

    @Test
    void rejectsPasswordWithFewerThan72CharsButMoreThan72Utf8Bytes() {
        String password = "Aa1" + "é".repeat(36);
        assertTrue(password.length() < PortalPasswordValidator.MAX_LENGTH);
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > PortalPasswordValidator.MAX_LENGTH);
        List<String> messages = PortalPasswordValidator.violationMessages(password);
        assertEquals(List.of("size must be between 12 and 72"), messages);
    }

    @Test
    void rejectsPasswordWithoutUppercaseLetter() {
        List<String> messages = PortalPasswordValidator.violationMessages("alllowercase1");
        assertEquals(List.of("Password must include an uppercase letter"), messages);
    }

    @Test
    void rejectsPasswordWithoutLowercaseLetter() {
        List<String> messages = PortalPasswordValidator.violationMessages("ALLUPPERCASE1");
        assertEquals(List.of("Password must include a lowercase letter"), messages);
    }

    @Test
    void rejectsPasswordWithoutDigit() {
        List<String> messages = PortalPasswordValidator.violationMessages("NoDigitsHereAb");
        assertEquals(List.of("Password must include a number"), messages);
    }

    @Test
    void reportsMultipleViolationsTogether() {
        List<String> messages = PortalPasswordValidator.violationMessages("short");
        assertFalse(messages.isEmpty());
        assertTrue(messages.contains("size must be between 12 and 72"));
        assertTrue(messages.contains("Password must include an uppercase letter"));
        assertTrue(messages.contains("Password must include a number"));
    }
}
