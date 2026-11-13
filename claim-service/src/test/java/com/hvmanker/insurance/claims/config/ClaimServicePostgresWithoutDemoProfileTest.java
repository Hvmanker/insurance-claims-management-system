package com.hvmanker.insurance.claims.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClaimServicePostgresWithoutDemoProfileTest extends ClaimServicePostgresTestSupport {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void portalUsersEmptyAfterStartupWithoutDemoProfile() {
        assertFalse(java.util.Arrays.asList(environment.getActiveProfiles()).contains("demo"));

        Integer migratedVersions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claims.flyway_schema_history_claim_service "
                        + "WHERE success AND version IN ('1','2','3','4')",
                Integer.class);
        assertEquals(4, migratedVersions);

        Integer portalUsers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claims.portal_users",
                Integer.class);
        assertEquals(0, portalUsers);
    }
}
