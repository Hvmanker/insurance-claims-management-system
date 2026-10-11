package com.sunil.insurance.claims.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sunil.insurance.claims.domain.PortalUserRepository;
import com.sunil.insurance.claims.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private static final ApplicationArguments NO_ARGS = mock(ApplicationArguments.class);

    @Autowired
    private DemoProfileSeeder seeder;

    @Autowired
    private PortalUserRepository users;

    @Test
    void secondRunDoesNotCreateDuplicateUsers() {
        seeder.run(NO_ARGS);
        assertEquals(2, users.count());

        UUID agentId = users.findByEmailIgnoreCase(DemoProfileSeeder.AGENT_EMAIL).orElseThrow().getId();
        UUID insuredId = users.findByEmailIgnoreCase(DemoProfileSeeder.INSURED_EMAIL).orElseThrow().getId();

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

        String combined = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertFalse(combined.contains("UniqueAgentPasswordForTests-7x"));
        assertFalse(combined.contains("UniqueInsuredPasswordForTests-9z"));
        assertFalse(combined.contains("$2"));
    }

    @Test
    void failsFastWhenDemoPasswordMissing() {
        assertThrows(IllegalStateException.class, () -> new DemoProfileSeeder(
                mock(PortalUserRepository.class),
                new BCryptPasswordEncoder(12),
                "",
                "present"));
    }

    @TestConfiguration
    static class PasswordEncoderConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(12);
        }
    }
}
