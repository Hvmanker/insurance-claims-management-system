package com.sunil.insurance.claims.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("demo")
@Import({DemoProfileSeeder.class, DemoProfileSeederTest.SupportConfig.class})
@TestPropertySource(properties = {
        "DEMO_AGENT_PASSWORD=UniqueAgentPasswordForTests-7x",
        "DEMO_INSURED_PASSWORD=UniqueInsuredPasswordForTests-9z",
        "DEMO_BROKER_PASSWORD=UniqueBrokerPasswordForTests-1a",
        "DEMO_ADJUSTER_PASSWORD=UniqueAdjusterPasswordForTests-2b",
        "DEMO_SUPERVISOR_PASSWORD=UniqueSupervisorPasswordForTests-3c",
        "DEMO_SIU_PASSWORD=UniqueSiuPasswordForTests-4d",
        "DEMO_FINANCE_PASSWORD=UniqueFinancePasswordForTests-5e",
        "DEMO_AUDITOR_PASSWORD=UniqueAuditorPasswordForTests-6f",
        "DEMO_ADMIN_PASSWORD=UniqueAdminPasswordForTests-8g",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.flyway.enabled=false"
})
class DemoProfileSeederTest {
    private static final String AGENT_PASSWORD = "UniqueAgentPasswordForTests-7x";
    private static final String INSURED_PASSWORD = "UniqueInsuredPasswordForTests-9z";
    private static final ApplicationArguments NO_ARGS = mock(ApplicationArguments.class);

    private static final Set<UserRole> EXPECTED_ROLES = EnumSet.of(
            UserRole.INSURED,
            UserRole.AGENT,
            UserRole.BROKER,
            UserRole.ADJUSTER,
            UserRole.SUPERVISOR,
            UserRole.SIU,
            UserRole.FINANCE,
            UserRole.AUDITOR,
            UserRole.ADMIN);

    @Autowired
    private DemoProfileSeeder seeder;

    @Autowired
    private PortalUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedsAllEightRolesPlusLegacyAgent() throws Exception {
        seeder.run(NO_ARGS);
        assertEquals(9, users.count());
        Set<UserRole> seededRoles = users.findAll().stream()
                .map(user -> user.getRole())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRole.class)));
        assertEquals(EXPECTED_ROLES, seededRoles);
        assertEquals(9, seeder.loadPersonaFixtures().size());
    }

    @Test
    void secondRunDoesNotCreateDuplicateUsers() throws Exception {
        seeder.run(NO_ARGS);
        assertEquals(9, users.count());

        UUID agentId = users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getId();
        UUID insuredId = users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getId();

        var agent = users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow();
        var insured = users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow();
        assertTrue(passwordEncoder.matches(AGENT_PASSWORD, agent.getPasswordHash()));
        assertTrue(passwordEncoder.matches(INSURED_PASSWORD, insured.getPasswordHash()));

        seeder.run(NO_ARGS);

        assertEquals(9, users.count());
        assertEquals(agentId, users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getId());
        assertEquals(insuredId, users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getId());
        assertEquals(UserRole.AGENT, users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getRole());
        assertEquals(UserRole.INSURED, users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getRole());
    }

    @Test
    void logsDoNotContainPasswordOrBcryptPrefix() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DemoProfileSeeder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            seeder.run(NO_ARGS);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(9, appender.list.size());

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
        assertTrue(combined.contains("BROKER"));
    }

    @Test
    void failsFastWhenDemoPasswordMissingOnRun() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(argThat(
                prop -> prop != null && prop.startsWith("DEMO_") && !"DEMO_ADMIN_PASSWORD".equals(prop))))
                .thenReturn("present");
        when(environment.getProperty("DEMO_ADMIN_PASSWORD")).thenReturn("   ");
        DemoProfileSeeder missingPasswordSeeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                environment,
                new ObjectMapper());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> missingPasswordSeeder.run(NO_ARGS));
        assertTrue(error.getMessage().contains("DEMO_ADMIN_PASSWORD"));
        assertFalse(error.getMessage().contains("present"));
    }

    @TestConfiguration
    static class SupportConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(12);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
