package com.hvmanker.insurance.claims.config;

import com.hvmanker.insurance.claims.ClaimServiceApplication;
import com.hvmanker.insurance.claims.domain.PortalUserRepository;
import com.hvmanker.insurance.claims.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = ClaimServiceApplication.class)
@ActiveProfiles("demo")
@EmbeddedKafka(partitions = 1, topics = {
        "claim.submitted",
        "claim.manual-decision",
        "claim.approved",
        "claim.rejected",
        "claim.settled"
})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:claim-demo-startup;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.flyway.enabled=false",
        "spring.cache.type=simple",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "DEMO_AGENT_PASSWORD=StartupDemoAgentPassword-7x",
        "DEMO_INSURED_PASSWORD=StartupDemoInsuredPassword-9z",
        "DEMO_BROKER_PASSWORD=StartupDemoBrokerPassword-1a",
        "DEMO_ADJUSTER_PASSWORD=StartupDemoAdjusterPassword-2b",
        "DEMO_SUPERVISOR_PASSWORD=StartupDemoSupervisorPassword-3c",
        "DEMO_SIU_PASSWORD=StartupDemoSiuPassword-4d",
        "DEMO_FINANCE_PASSWORD=StartupDemoFinancePassword-5e",
        "DEMO_AUDITOR_PASSWORD=StartupDemoAuditorPassword-6f",
        "DEMO_ADMIN_PASSWORD=StartupDemoAdminPassword-8g"
})
class ClaimServiceDemoProfileStartupTest {

    private static final Map<String, UserRole> EXPECTED_SEEDED_USERS = Map.ofEntries(
            Map.entry("adjuster@insureflow.local", UserRole.ADJUSTER),
            Map.entry("admin@insureflow.local", UserRole.ADMIN),
            Map.entry("agent@insureflow.local", UserRole.AGENT),
            Map.entry("auditor@insureflow.local", UserRole.AUDITOR),
            Map.entry("broker@insureflow.local", UserRole.BROKER),
            Map.entry("finance@insureflow.local", UserRole.FINANCE),
            Map.entry("insured@insureflow.local", UserRole.INSURED),
            Map.entry("siu@insureflow.local", UserRole.SIU),
            Map.entry("supervisor@insureflow.local", UserRole.SUPERVISOR));

    @Autowired
    private PortalUserRepository users;

    @Test
    void bootsWithDemoProfileAndSeedsPortalUsers() {
        assertEquals(EXPECTED_SEEDED_USERS.size(), users.count());
        EXPECTED_SEEDED_USERS.forEach((email, expectedRole) -> {
            var user = users.findByEmailIgnoreCase(email).orElseThrow(
                    () -> new AssertionError("Missing seeded portal user: " + email));
            assertEquals(email, user.getEmail(), "username for " + email);
            assertEquals(expectedRole, user.getRole(), "role for " + email);
        });
    }
}
