package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.config.ClaimEventPublisher;
import com.sunil.insurance.claims.domain.Claim;
import com.sunil.insurance.claims.domain.ClaimRepository;
import com.sunil.insurance.claims.web.ClaimResponse;
import com.sunil.insurance.claims.web.SubmitClaimRequest;
import com.sunil.insurance.common.ClaimStatus;
import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ClaimService {
    private final ClaimRepository claimRepository;
    private final ClaimEventPublisher eventPublisher;

    public ClaimService(ClaimRepository claimRepository, ClaimEventPublisher eventPublisher) {
        this.claimRepository = claimRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ClaimResponse submit(SubmitClaimRequest request) {
        var claim = new Claim(
                UUID.randomUUID(),
                request.policyNumber(),
                request.claimantName(),
                request.claimType(),
                request.estimatedAmount(),
                ClaimStatus.SUBMITTED,
                Instant.now()
        );
        Claim saved = claimRepository.save(claim);
        eventPublisher.publish(new ClaimSubmittedEvent(
                saved.getId(),
                saved.getPolicyNumber(),
                saved.getClaimantName(),
                saved.getClaimType(),
                saved.getEstimatedAmount(),
                saved.getSubmittedAt()
        ));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "claims", key = "#p0")
    public ClaimResponse get(UUID claimId) {
        return claimRepository.findById(claimId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
    }

    @Transactional
    @CacheEvict(cacheNames = "claims", key = "#p0")
    public void updateStatus(UUID claimId, ClaimStatus status) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        claim.setStatus(status);
    }

    private ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getPolicyNumber(),
                claim.getClaimantName(),
                claim.getClaimType(),
                claim.getEstimatedAmount(),
                claim.getStatus(),
                claim.getSubmittedAt()
        );
    }
}
