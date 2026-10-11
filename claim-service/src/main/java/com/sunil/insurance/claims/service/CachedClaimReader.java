package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.domain.Claim;
import com.sunil.insurance.claims.domain.ClaimRepository;
import com.sunil.insurance.claims.web.ClaimResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Loads claim payloads by id only. Authorization runs in {@link ClaimService} before calling here.
 */
@Service
public class CachedClaimReader {
    private final ClaimRepository claimRepository;

    public CachedClaimReader(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "claims", key = "#p0")
    public ClaimResponse getById(UUID claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        return toResponse(claim);
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
