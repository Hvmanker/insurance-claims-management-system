package com.sunil.insurance.claims.config;

import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.domain.PortalUserRepository;
import com.sunil.insurance.claims.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

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
    private final String agentPassword;
    private final String insuredPassword;

    public DemoProfileSeeder(
            PortalUserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${DEMO_AGENT_PASSWORD:}") String agentPassword,
            @Value("${DEMO_INSURED_PASSWORD:}") String insuredPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.agentPassword = requirePassword("DEMO_AGENT_PASSWORD", agentPassword);
        this.insuredPassword = requirePassword("DEMO_INSURED_PASSWORD", insuredPassword);
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUser(AGENT_ID, AGENT_EMAIL, "Morgan Rivera", UserRole.AGENT, agentPassword);
        seedUser(INSURED_ID, INSURED_EMAIL, "Ava Thompson", UserRole.INSURED, insuredPassword);
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

    private static String requirePassword(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set when the demo profile is active");
        }
        return value;
    }
}
