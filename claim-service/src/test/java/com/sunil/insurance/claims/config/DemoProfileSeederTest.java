package com.sunil.insurance.claims.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sunil.insurance.claims.domain.PortalUserRepository;
import com.sunil.insurance.claims.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@ActiveProfiles("demo")
@Import({DemoProfileSeeder.class, DemoProfileSeederTest.PasswordEncoderConfig.class})
@TestPropertySource(properties = {
        "DEMO_AGENT_PASSWORD=UniqueAgentPasswordForTests-7x",
        "DEMO_INSURED_PASSWORD=UniqueInsuredPasswordForTests-9z",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.flyway.enabled=false"
})
class DemoProfileSeederTest {
    private static final String AGENT_PASSWORD = "UniqueAgentPasswordForTests-7x";
    private static final String INSURED_PASSWORD = "UniqueInsuredPasswordForTests-9z";
    private static final ApplicationArguments NO_ARGS = mock(ApplicationArguments.class);

    @Autowired
    private DemoProfileSeeder seeder;

    @Autowired
    private PortalUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void secondRunDoesNotCreateDuplicateUsers() {
        seeder.run(NO_ARGS);
        assertEquals(2, users.count());

        UUID agentId = users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getId();
        UUID insuredId = users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getId();

        var agent = users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow();
        var insured = users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow();
        assertTrue(passwordEncoder.matches(AGENT_PASSWORD, agent.getPasswordHash()));
        assertTrue(passwordEncoder.matches(INSURED_PASSWORD, insured.getPasswordHash()));

        seeder.run(NO_ARGS);

        assertEquals(2, users.count());
        assertEquals(agentId, users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getId());
        assertEquals(insuredId, users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getId());
        assertEquals(UserRole.AGENT, users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getRole());
        assertEquals(UserRole.INSURED, users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getRole());
    }

    @Test
    void logsDoNotContainPasswordOrBcryptPrefix() {
        Logger logger = (Logger) LoggerFactory.getLogger(DemoProfileSeeder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            seeder.run(NO_ARGS);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(2, appender.list.size());

        String combined = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertFalse(combined.contains(AGENT_PASSWORD));
        assertFalse(combined.contains(INSURED_PASSWORD));
        assertFalse(combined.contains("$2"));
        assertTrue(combined.contains(DemoProfileSeeder.AGENT_EMAIL));
        assertTrue(combined.contains(DemoProfileSeeder.INSURED_EMAIL));
        assertTrue(combined.contains("AGENT"));
        assertTrue(combined.contains("INSURED"));
    }

    @Test
    void failsFastWhenAgentPasswordMissing() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                "",
                "present"));
        assertTrue(error.getMessage().contains("DEMO_AGENT_PASSWORD"));
        assertFalse(error.getMessage().contains("present"));
    }

    @Test
    void failsFastWhenInsuredPasswordMissing() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                "present",
                ""));
        assertTrue(error.getMessage().contains("DEMO_INSURED_PASSWORD"));
        assertFalse(error.getMessage().contains("present"));
    }

    @Test
    void failsFastWhenDemoPasswordsAreWhitespaceOnly() {
        IllegalStateException agentError = assertThrows(IllegalStateException.class, () -> new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                "   ",
                "present"));
        assertTrue(agentError.getMessage().contains("DEMO_AGENT_PASSWORD"));

        IllegalStateException insuredError = assertThrows(IllegalStateException.class, () -> new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                "present",
                "  "));
        assertTrue(insuredError.getMessage().contains("DEMO_INSURED_PASSWORD"));
    }

    @TestConfiguration
    static class PasswordEncoderConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(12);
        }
    }
}
