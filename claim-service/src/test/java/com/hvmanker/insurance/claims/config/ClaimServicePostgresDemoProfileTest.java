package com.hvmanker.insurance.claims.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("demo")
@TestPropertySource(properties = {
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
class ClaimServicePostgresDemoProfileTest extends ClaimServicePostgresTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void demoProfileSeedsPortalUsersOnPostgres() {
        Integer portalUsers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claims.portal_users",
                Integer.class);
        assertEquals(9, portalUsers);
    }
}
