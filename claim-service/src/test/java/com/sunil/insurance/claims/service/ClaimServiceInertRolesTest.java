package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.config.ClaimEventPublisher;
import com.sunil.insurance.claims.domain.ClaimEventRepository;
import com.sunil.insurance.claims.domain.ClaimRepository;
import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.domain.UserRole;
import com.sunil.insurance.claims.web.ManualDecisionRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class ClaimServiceInertRolesTest {
    private static final UUID CLAIM_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private ClaimEventRepository claimEventRepository;
    @Mock
    private ClaimEventPublisher eventPublisher;

    @InjectMocks
    private ClaimService claimService;

    private PortalUser inertUser;

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {
            "BROKER", "ADJUSTER", "SUPERVISOR", "SIU", "FINANCE", "AUDITOR", "ADMIN"
    })
    void newRolesHaveNoClaimVisibility(UserRole role) {
        inertUser = portalUser(role, UUID.randomUUID());

        assertEquals(0, claimService.recentClaims(inertUser).size());

        ClaimService.ClaimDashboardSummary summary = claimService.dashboardSummary(inertUser);
        assertEquals(0, summary.totalClaims());
        assertEquals(0, summary.submittedClaims());
        assertEquals(0, summary.approvedClaims());
        assertEquals(0, summary.rejectedClaims());
        assertEquals(0, summary.settledClaims());
        assertEquals(BigDecimal.ZERO, summary.totalEstimatedAmount());

        ResponseStatusException getError = assertThrows(ResponseStatusException.class,
                () -> claimService.get(CLAIM_ID, inertUser));
        assertEquals(NOT_FOUND, getError.getStatusCode());

        ManualDecisionRequest decision = new ManualDecisionRequest(true, "Looks valid");
        ResponseStatusException decideError = assertThrows(ResponseStatusException.class,
                () -> claimService.decide(CLAIM_ID, decision, inertUser));
        assertEquals(FORBIDDEN, decideError.getStatusCode());
    }

    private static PortalUser portalUser(UserRole role, UUID id) {
        return new PortalUser(id, role.name().toLowerCase() + "@test.local", "Test User", "hash", role, Instant.now());
    }
}
