package com.hvmanker.insurance.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared portal password rules for registration and demo profile seeding.
 */
public final class PortalPasswordValidator {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 72;

    private static final Pattern UPPERCASE = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT = Pattern.compile(".*[0-9].*");

    private PortalPasswordValidator() {
    }

    public static List<String> violationMessages(String password) {
        List<String> messages = new ArrayList<>();
        if (password == null) {
            return messages;
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            messages.add("size must be between " + MIN_LENGTH + " and " + MAX_LENGTH);
        }
        if (!UPPERCASE.matcher(password).matches()) {
            messages.add("Password must include an uppercase letter");
        }
        if (!LOWERCASE.matcher(password).matches()) {
            messages.add("Password must include a lowercase letter");
        }
        if (!DIGIT.matcher(password).matches()) {
            messages.add("Password must include a number");
        }
        return messages;
    }

    public static boolean isValid(String password) {
        return password != null && violationMessages(password).isEmpty();
    }

    public static String requireForDemoEnvironment(String envVarName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(envVarName + " must be set when the demo profile is active");
        }
        if (!isValid(value)) {
            throw new IllegalStateException(
                    envVarName + " does not meet portal password requirements (length "
                            + MIN_LENGTH
                            + "-"
                            + MAX_LENGTH
                            + ", uppercase, lowercase, digit)");
        }
        return value;
    }
}
