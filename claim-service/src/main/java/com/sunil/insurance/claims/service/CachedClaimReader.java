package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.domain.Claim;
import com.sunil.insurance.claims.web.ClaimResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Loads claim payloads by id only. Authorization runs in {@link ClaimService} before calling here.
 */
@Service
public class CachedClaimReader {
    private final ClaimPayloadLoader claimPayloadLoader;

    public CachedClaimReader(ClaimPayloadLoader claimPayloadLoader) {
        this.claimPayloadLoader = claimPayloadLoader;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "claims", key = "#p0")
    public ClaimResponse getById(UUID claimId) {
        return toResponse(claimPayloadLoader.loadById(claimId));
    }

    private static ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getPolicyNumber(),
                claim.getClaimantName(),
                claim.getClaimType(),
                claim.getReason(),
                claim.getDescription(),
                claim.getEstimatedAmount(),
                claim.getStatus(),
                claim.getSubmittedAt()
        );
    }
}
