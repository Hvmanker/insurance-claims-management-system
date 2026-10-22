package com.hvmanker.insurance.claims.service;

import com.hvmanker.insurance.claims.config.ClaimEventPublisher;
import com.hvmanker.insurance.claims.domain.ClaimEventRepository;
import com.hvmanker.insurance.claims.domain.ClaimRepository;
import com.hvmanker.insurance.common.ClaimStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class ClaimServiceStatusTitleTest {

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private ClaimEventRepository claimEventRepository;
    @Mock
    private ClaimEventPublisher eventPublisher;
    @Mock
    private CachedClaimReader cachedClaimReader;

    @InjectMocks
    private ClaimService claimService;

    @Test
    void statusTitleAnalysisPending() {
        String title = claimService.statusTitle(ClaimStatus.ANALYSIS_PENDING);
        assertEquals("Claim sent for analysis", title);
        assertNotEquals(claimService.statusTitle(ClaimStatus.SUBMITTED), title);
        for (ClaimStatus status : ClaimStatus.values()) {
            String statusTitle = claimService.statusTitle(status);
            assertNotNull(statusTitle);
            assertFalse(statusTitle.isBlank());
        }
        Set<String> titles = new HashSet<>();
        for (ClaimStatus status : ClaimStatus.values()) {
            titles.add(claimService.statusTitle(status));
        }
        assertEquals(ClaimStatus.values().length, titles.size());
    }

    @Test
    void statusTitleReviewReady() {
        String title = claimService.statusTitle(ClaimStatus.REVIEW_READY);
        assertEquals("Claim ready for review", title);
        assertNotEquals(claimService.statusTitle(ClaimStatus.SUBMITTED), title);
        for (ClaimStatus status : ClaimStatus.values()) {
            String statusTitle = claimService.statusTitle(status);
            assertNotNull(statusTitle);
            assertFalse(statusTitle.isBlank());
        }
        Set<String> titles = new HashSet<>();
        for (ClaimStatus status : ClaimStatus.values()) {
            titles.add(claimService.statusTitle(status));
        }
        assertEquals(ClaimStatus.values().length, titles.size());
    }
}
