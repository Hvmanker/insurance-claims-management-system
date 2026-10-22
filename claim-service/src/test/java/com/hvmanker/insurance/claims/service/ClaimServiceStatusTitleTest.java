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
        assertNotNull(title);
        assertFalse(title.isBlank());
        assertNotEquals(claimService.statusTitle(ClaimStatus.SUBMITTED), title);
    }

    @Test
    void statusTitleReviewReady() {
        String title = claimService.statusTitle(ClaimStatus.REVIEW_READY);
        assertNotNull(title);
        assertFalse(title.isBlank());
        assertNotEquals(claimService.statusTitle(ClaimStatus.SUBMITTED), title);
    }
}
