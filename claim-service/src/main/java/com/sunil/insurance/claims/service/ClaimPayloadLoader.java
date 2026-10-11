package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.domain.Claim;
import com.sunil.insurance.claims.domain.ClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Loads claim entities by id for the read cache. Authorization stays in {@link ClaimService}.
 */
@Service
public class ClaimPayloadLoader {
    private final ClaimRepository claimRepository;

    public ClaimPayloadLoader(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Transactional(readOnly = true)
    public Claim loadById(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
    }
}
