package com.hvmanker.insurance.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimStatusTest {

    @Test
    void containsAnalysisPendingAndReviewReady() {
        assertNotNull(ClaimStatus.valueOf("ANALYSIS_PENDING"));
        assertNotNull(ClaimStatus.valueOf("REVIEW_READY"));
    }

    @Test
    void keepsExistingStates() {
        assertNotNull(ClaimStatus.valueOf("SUBMITTED"));
        assertNotNull(ClaimStatus.valueOf("APPROVED"));
        assertNotNull(ClaimStatus.valueOf("REJECTED"));
        assertNotNull(ClaimStatus.valueOf("SETTLED"));
    }

    @Test
    void everyStatusFitsVarchar20() {
        for (ClaimStatus status : ClaimStatus.values()) {
            assertTrue(status.name().length() <= 20, status.name());
        }
    }
}
