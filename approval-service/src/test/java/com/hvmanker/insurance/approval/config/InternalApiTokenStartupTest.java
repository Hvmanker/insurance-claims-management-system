package com.hvmanker.insurance.approval.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiTokenStartupTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(InternalApiSecurityConfig.class);

    @Test
    void blankServiceTokenFailsStartup() {
        contextRunner.withPropertyValues("INTERNAL_API_TOKEN=").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root instanceof IllegalStateException);
            assertTrue(root.getMessage().contains("INTERNAL_API_TOKEN"));
            assertFalse(root.getMessage().contains("test-internal-token-1A3"));
        });

        contextRunner.withPropertyValues("INTERNAL_API_TOKEN=   ").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root instanceof IllegalStateException);
            assertTrue(root.getMessage().contains("INTERNAL_API_TOKEN"));
            assertFalse(root.getMessage().contains("   "));
        });

        contextRunner.withPropertyValues("INTERNAL_API_TOKEN=test-internal-token-1A3").run(context -> {
            assertThat(context).hasNotFailed();
        });
    }
}
