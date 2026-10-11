package com.hvmanker.insurance.claims.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.claims.domain.PortalUser;
import com.hvmanker.insurance.claims.domain.PortalUserRepository;
import com.hvmanker.insurance.claims.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
@Profile("demo")
public class DemoProfileSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoProfileSeeder.class);

    static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID INSURED_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final String AGENT_EMAIL = "agent@insureflow.local";
    static final String INSURED_EMAIL = "insured@insureflow.local";

    private final PortalUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public DemoProfileSeeder(
            PortalUserRepository users,
            PasswordEncoder passwordEncoder,
            Environment environment,
            ObjectMapper objectMapper) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        for (PersonaFixture persona : loadPersonaFixtures()) {
            String rawPassword = requirePassword(persona.passwordEnv(), environment.getProperty(persona.passwordEnv()));
            seedUser(persona.id(), persona.email(), persona.fullName(), persona.role(), rawPassword);
        }
    }

    List<PersonaFixture> loadPersonaFixtures() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:fixtures/personas/*.json");
        List<PersonaFixture> personas = new ArrayList<>();
        for (Resource resource : resources) {
            PersonaFixture persona = objectMapper.readValue(resource.getInputStream(), PersonaFixture.class);
            if (persona.role() != null && persona.passwordEnv() != null) {
                personas.add(persona);
            }
        }
        personas.sort(Comparator.comparing(PersonaFixture::email));
        return personas;
    }

    void seedUser(UUID id, String email, String fullName, UserRole role, String rawPassword) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String passwordHash = passwordEncoder.encode(rawPassword);
        users.upsertByEmail(new PortalUser(
                id,
                normalizedEmail,
                fullName,
                passwordHash,
                role,
                Instant.now()));
        log.info("Demo portal user ready: email={}, role={}", normalizedEmail, role);
    }

    static String requirePassword(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set when the demo profile is active");
        }
        return value;
    }
}
