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
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<ClaimResponse> recentClaims() {
        return claimRepository.findTop50ByOrderBySubmittedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClaimDashboardSummary dashboardSummary() {
        EnumMap<ClaimStatus, Long> counts = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) {
            counts.put(status, 0L);
        }

        BigDecimal totalEstimatedAmount = BigDecimal.ZERO;
        for (Claim claim : claimRepository.findAll()) {
            counts.compute(claim.getStatus(), (status, count) -> count + 1);
            totalEstimatedAmount = totalEstimatedAmount.add(claim.getEstimatedAmount());
        }

        return new ClaimDashboardSummary(
                counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.get(ClaimStatus.SUBMITTED),
                counts.get(ClaimStatus.APPROVED),
                counts.get(ClaimStatus.REJECTED),
                counts.get(ClaimStatus.SETTLED),
                totalEstimatedAmount
        );
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

    public record ClaimDashboardSummary(
            long totalClaims,
            long submittedClaims,
            long approvedClaims,
            long rejectedClaims,
            long settledClaims,
            BigDecimal totalEstimatedAmount
    ) {
    }
}
