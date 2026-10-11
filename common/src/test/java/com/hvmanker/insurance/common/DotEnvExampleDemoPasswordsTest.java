package com.hvmanker.insurance.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotEnvExampleDemoPasswordsTest {

    private static final Pattern DEMO_PASSWORD_LINE =
            Pattern.compile("^(DEMO_[A-Z_]+_PASSWORD)=(.*)$");

    @Test
    void envExampleDemoPasswordPlaceholdersFailPortalRules() throws IOException {
        Path envExample = Path.of(System.getProperty("user.dir")).resolve("../.env.example").normalize();
        List<String> demoVars = new ArrayList<>();
        for (String line : Files.readAllLines(envExample)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = DEMO_PASSWORD_LINE.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(1);
            String value = matcher.group(2).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            demoVars.add(name);
            assertFalse(
                    PortalPasswordValidator.isValid(value),
                    () -> name + " in .env.example must remain a non-login placeholder (fails portal rules)");
        }
        assertTrue(demoVars.size() == 9, () -> "expected 9 DEMO_*_PASSWORD entries, got " + demoVars);
    }
}
