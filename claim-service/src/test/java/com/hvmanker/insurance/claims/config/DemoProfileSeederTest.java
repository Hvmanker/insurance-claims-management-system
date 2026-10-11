package com.hvmanker.insurance.claims.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.claims.domain.PortalUserRepository;
import com.hvmanker.insurance.claims.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static final List<String> EXPECTED_LOG_LINES_IN_SEED_ORDER = List.of(
            "Demo portal user ready: email=adjuster@insureflow.local, role=ADJUSTER",
            "Demo portal user ready: email=admin@insureflow.local, role=ADMIN",
            "Demo portal user ready: email=agent@insureflow.local, role=AGENT",
            "Demo portal user ready: email=auditor@insureflow.local, role=AUDITOR",
            "Demo portal user ready: email=broker@insureflow.local, role=BROKER",
            "Demo portal user ready: email=finance@insureflow.local, role=FINANCE",
            "Demo portal user ready: email=insured@insureflow.local, role=INSURED",
            "Demo portal user ready: email=siu@insureflow.local, role=SIU",
            "Demo portal user ready: email=supervisor@insureflow.local, role=SUPERVISOR");

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

        List<String> loggedMessages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(EXPECTED_LOG_LINES_IN_SEED_ORDER, loggedMessages);

        String combined = String.join("\n", loggedMessages);

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
    void rejectsRedisPasswordEnvWithoutReadingEnvironmentOrHashing() throws Exception {
        assertRejectsInvalidPasswordEnv(
                "evil-redis-agent.json",
                "REDIS_PASSWORD",
                """
                {
                  "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "email": "evil-redis@insureflow.local",
                  "fullName": "Evil Redis",
                  "role": "AGENT",
                  "passwordEnv": "REDIS_PASSWORD"
                }
                """);
    }

    @Test
    void rejectsPostgresPasswordEnvWithoutReadingEnvironmentOrHashing() throws Exception {
        assertRejectsInvalidPasswordEnv(
                "evil-postgres-agent.json",
                "POSTGRES_PASSWORD",
                """
                {
                  "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "email": "evil-postgres@insureflow.local",
                  "fullName": "Evil Postgres",
                  "role": "AGENT",
                  "passwordEnv": "POSTGRES_PASSWORD"
                }
                """);
    }

    private void assertRejectsInvalidPasswordEnv(String fixtureFile, String passwordEnv, String json) throws Exception {
        Environment environment = mock(Environment.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PortalUserRepository users = mock(PortalUserRepository.class);

        Resource resource = mock(Resource.class);
        when(resource.getFilename()).thenReturn(fixtureFile);
        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(DemoProfileSeeder.PERSONA_FIXTURES_PATTERN)).thenReturn(new Resource[] {resource});

        DemoProfileSeeder seeder = new DemoProfileSeeder(users, passwordEncoder, environment, new ObjectMapper());

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> seeder.loadPersonaFixtures(resolver));
        assertTrue(error.getMessage().contains(fixtureFile));
        assertTrue(error.getMessage().contains(passwordEnv));

        verify(environment, never()).getProperty(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(users, never()).upsertByEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsFastWhenNoPersonaFixturesOnClasspath() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(DemoProfileSeeder.PERSONA_FIXTURES_PATTERN)).thenReturn(new Resource[0]);
        DemoProfileSeeder emptyFixturesSeeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                mock(Environment.class),
                new ObjectMapper());
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> emptyFixturesSeeder.loadPersonaFixtures(resolver));
        assertEquals(DemoProfileSeeder.MISSING_PERSONA_FIXTURES_MESSAGE, error.getMessage());
    }

    @Test
    void failsFastWhenAgentPasswordMissing() throws Exception {
        Environment environment = environmentWithPassword("DEMO_AGENT_PASSWORD", "");
        DemoProfileSeeder missingPasswordSeeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                environment,
                new ObjectMapper());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> missingPasswordSeeder.run(NO_ARGS));
        assertTrue(error.getMessage().contains("DEMO_AGENT_PASSWORD"));
        assertFalse(error.getMessage().contains("present"));
    }

    @Test
    void failsFastWhenInsuredPasswordMissing() throws Exception {
        Environment environment = environmentWithPassword("DEMO_INSURED_PASSWORD", "");
        DemoProfileSeeder missingPasswordSeeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                environment,
                new ObjectMapper());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> missingPasswordSeeder.run(NO_ARGS));
        assertTrue(error.getMessage().contains("DEMO_INSURED_PASSWORD"));
        assertFalse(error.getMessage().contains("present"));
    }

    @Test
    void rejectsWeakDemoPasswordWithoutEchoingValue() {
        String weakPassword = "Short1Only";
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> DemoProfileSeeder.requirePassword("DEMO_AGENT_PASSWORD", weakPassword));
        assertTrue(error.getMessage().contains("DEMO_AGENT_PASSWORD"));
        assertFalse(error.getMessage().contains(weakPassword));
    }

    @Test
    void rejectsWeakDemoInsuredPasswordWithoutEchoingValueOrLogLeak() throws Exception {
        String weakInsuredPassword = "nouppercase1";
        Environment environment = mock(Environment.class);
        when(environment.getProperty(argThat(
                prop -> prop != null && prop.startsWith("DEMO_") && !"DEMO_INSURED_PASSWORD".equals(prop))))
                .thenReturn(STRONG_DEMO_PASSWORD_PLACEHOLDER);
        when(environment.getProperty("DEMO_INSURED_PASSWORD")).thenReturn(weakInsuredPassword);

        Logger logger = (Logger) LoggerFactory.getLogger(DemoProfileSeeder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        DemoProfileSeeder seeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                environment,
                new ObjectMapper());
        try {
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> seeder.run(NO_ARGS));
            assertTrue(error.getMessage().contains("DEMO_INSURED_PASSWORD"));
            assertFalse(error.getMessage().contains(weakInsuredPassword));
        } finally {
            logger.detachAppender(appender);
        }

        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertFalse(logged.contains(weakInsuredPassword));
    }

    @Test
    void failsFastWhenDemoPasswordsAreWhitespaceOnly() throws Exception {
        Environment agentEnvironment = environmentWithPassword("DEMO_AGENT_PASSWORD", "   ");
        DemoProfileSeeder agentSeeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                agentEnvironment,
                new ObjectMapper());
        IllegalStateException agentError = assertThrows(IllegalStateException.class, () -> agentSeeder.run(NO_ARGS));
        assertTrue(agentError.getMessage().contains("DEMO_AGENT_PASSWORD"));

        Environment insuredEnvironment = environmentWithPassword("DEMO_INSURED_PASSWORD", "  ");
        DemoProfileSeeder insuredSeeder = new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                insuredEnvironment,
                new ObjectMapper());
        IllegalStateException insuredError = assertThrows(IllegalStateException.class, () -> insuredSeeder.run(NO_ARGS));
        assertTrue(insuredError.getMessage().contains("DEMO_INSURED_PASSWORD"));
    }

    private static final String STRONG_DEMO_PASSWORD_PLACEHOLDER = "StrongDemoPass-9z";

    private static Environment environmentWithPassword(String propertyName, String value) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(argThat(
                prop -> prop != null && prop.startsWith("DEMO_") && !propertyName.equals(prop))))
                .thenReturn(STRONG_DEMO_PASSWORD_PLACEHOLDER);
        when(environment.getProperty(propertyName)).thenReturn(value);
        return environment;
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
