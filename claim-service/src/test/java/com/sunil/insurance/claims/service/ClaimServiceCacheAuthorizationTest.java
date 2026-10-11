package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.config.ClaimEventPublisher;
import com.sunil.insurance.claims.domain.Claim;
import com.sunil.insurance.claims.domain.ClaimRepository;
import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.domain.UserRole;
import com.sunil.insurance.claims.web.ClaimResponse;
import com.sunil.insurance.claims.web.ManualDecisionRequest;
import com.sunil.insurance.common.ClaimStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * TM-A: claim read cache must not bypass authorization; decide must evict stale entries.
 */
@DataJpaTest
@EnableCaching
@Import({
        ClaimService.class,
        CachedClaimReader.class,
        ClaimServiceCacheAuthorizationTest.SupportConfig.class
})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.flyway.enabled=false"
})
class ClaimServiceCacheAuthorizationTest {

    private static final UUID CLAIM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CLAIM_ID_INSURED_B = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLAIM_ID_INSURED_C = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INSURED_B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID INSURED_C_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID BROKER_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Autowired
    private ClaimService claimService;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void seedClaim() {
        cacheManager.getCache("claims").clear();
        claimRepository.deleteAll();
        claimRepository.save(new Claim(
                CLAIM_ID,
                "POL-TM-A-001",
                "Insured A",
                "AUTO",
                "Collision",
                "Test claim for TM-A cache authorization",
                OWNER_A_ID,
                BigDecimal.valueOf(5000),
                ClaimStatus.SUBMITTED,
                Instant.parse("2026-01-15T10:00:00Z")
        ));
        claimRepository.save(new Claim(
                CLAIM_ID_INSURED_B,
                "POL-TM-A-002",
                "Insured B",
                "HOME",
                "Water damage",
                "Second insured claim",
                INSURED_B_ID,
                BigDecimal.valueOf(8000),
                ClaimStatus.SUBMITTED,
                Instant.parse("2026-01-16T10:00:00Z")
        ));
        claimRepository.save(new Claim(
                CLAIM_ID_INSURED_C,
                "POL-TM-A-003",
                "Insured C",
                "AUTO",
                "Theft",
                "Third insured claim",
                INSURED_C_ID,
                BigDecimal.valueOf(12000),
                ClaimStatus.SUBMITTED,
                Instant.parse("2026-01-17T10:00:00Z")
        ));
    }

    @Test
    void insuredBCannotReadClaimAfterAgentWarmedCache() {
        PortalUser agent = user(AGENT_ID, UserRole.AGENT);
        PortalUser insuredB = user(INSURED_B_ID, UserRole.INSURED);

        ClaimResponse agentView = claimService.get(CLAIM_ID, agent);
        assertEquals(ClaimStatus.SUBMITTED, agentView.status());

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> claimService.get(CLAIM_ID, insuredB));
        assertEquals(NOT_FOUND, denied.getStatusCode());
    }

    @Test
    void brokerCannotReadClaimAfterAgentWarmedCache() {
        PortalUser agent = user(AGENT_ID, UserRole.AGENT);
        PortalUser broker = user(BROKER_ID, UserRole.BROKER);

        claimService.get(CLAIM_ID, agent);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> claimService.get(CLAIM_ID, broker));
        assertEquals(NOT_FOUND, denied.getStatusCode());
    }

    @Test
    void legacyAgentCanReadClaimsFromMultipleInsuredsWithColdAndWarmCache() {
        PortalUser agent = user(AGENT_ID, UserRole.AGENT);

        ClaimResponse coldReadB = claimService.get(CLAIM_ID_INSURED_B, agent);
        assertEquals(CLAIM_ID_INSURED_B, coldReadB.id());
        assertEquals("Insured B", coldReadB.claimantName());

        ClaimResponse coldReadC = claimService.get(CLAIM_ID_INSURED_C, agent);
        assertEquals(CLAIM_ID_INSURED_C, coldReadC.id());
        assertEquals("Insured C", coldReadC.claimantName());

        ClaimResponse warmedA = claimService.get(CLAIM_ID, agent);
        assertEquals(CLAIM_ID, warmedA.id());

        ClaimResponse warmReadB = claimService.get(CLAIM_ID_INSURED_B, agent);
        assertEquals(CLAIM_ID_INSURED_B, warmReadB.id());

        ClaimResponse warmReadC = claimService.get(CLAIM_ID_INSURED_C, agent);
        assertEquals(CLAIM_ID_INSURED_C, warmReadC.id());
    }

    @Test
    void agentSeesUpdatedStatusAfterDecideNotStaleCache() {
        PortalUser agent = user(AGENT_ID, UserRole.AGENT);

        ClaimResponse before = claimService.get(CLAIM_ID, agent);
        assertEquals(ClaimStatus.SUBMITTED, before.status());

        claimService.decide(CLAIM_ID, new ManualDecisionRequest(true, "Approved in TM-A test"), agent);

        ClaimResponse after = claimService.get(CLAIM_ID, agent);
        assertEquals(ClaimStatus.APPROVED, after.status());
    }

    private static PortalUser user(UUID id, UserRole role) {
        return new PortalUser(id, role.name().toLowerCase() + "@tm-a.test", "Test User", "hash", role, Instant.now());
    }

    @TestConfiguration
    static class SupportConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("claims");
        }

        @Bean
        ClaimEventPublisher claimEventPublisher() {
            return mock(ClaimEventPublisher.class);
        }
    }
}
