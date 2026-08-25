package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.config.ClaimEventPublisher;
import com.sunil.insurance.claims.domain.Claim;
import com.sunil.insurance.claims.domain.ClaimRepository;
import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.domain.UserRole;
import com.sunil.insurance.claims.web.ClaimResponse;
import com.sunil.insurance.claims.web.SubmitClaimRequest;
import com.sunil.insurance.claims.web.ManualDecisionRequest;
import com.sunil.insurance.common.ClaimStatus;
import com.sunil.insurance.common.events.ClaimSubmittedEvent;
import com.sunil.insurance.common.events.ManualClaimDecisionEvent;
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
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class ClaimService {
    private final ClaimRepository claimRepository;
    private final ClaimEventPublisher eventPublisher;

    public ClaimService(ClaimRepository claimRepository, ClaimEventPublisher eventPublisher) {
        this.claimRepository = claimRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ClaimResponse submit(SubmitClaimRequest request, PortalUser owner) {
        if (owner.getRole() != UserRole.INSURED) {
            throw new ResponseStatusException(FORBIDDEN, "Only insured members can submit claims");
        }
        var claim = new Claim(
                UUID.randomUUID(),
                request.policyNumber(),
                owner.getFullName(),
                request.claimType(),
                request.reason(),
                owner.getId(),
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
    public ClaimResponse get(UUID claimId, PortalUser user) {
        return toResponse(findVisibleClaim(claimId, user));
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> recentClaims(PortalUser user) {
        List<Claim> claims = user.getRole() == UserRole.AGENT
                ? claimRepository.findTop50ByOrderBySubmittedAtDesc()
                : claimRepository.findTop50ByOwnerIdOrderBySubmittedAtDesc(user.getId());
        return claims.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClaimDashboardSummary dashboardSummary(PortalUser user) {
        EnumMap<ClaimStatus, Long> counts = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) {
            counts.put(status, 0L);
        }

        BigDecimal totalEstimatedAmount = BigDecimal.ZERO;
        List<Claim> visibleClaims = user.getRole() == UserRole.AGENT
                ? claimRepository.findAll()
                : claimRepository.findTop50ByOwnerIdOrderBySubmittedAtDesc(user.getId());
        for (Claim claim : visibleClaims) {
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

    @Transactional
    public ClaimResponse decide(UUID claimId, ManualDecisionRequest request, PortalUser agent) {
        if (agent.getRole() != UserRole.AGENT) {
            throw new ResponseStatusException(FORBIDDEN, "Only agents can make claim decisions");
        }
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw new ResponseStatusException(FORBIDDEN, "This claim is no longer awaiting a decision");
        }
        eventPublisher.publishManualDecision(new ManualClaimDecisionEvent(
                claim.getId(), claim.getPolicyNumber(), claim.getEstimatedAmount(), request.approved(),
                request.reason().trim(), agent.getFullName(), Instant.now()));
        return toResponse(claim);
    }

    private ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getPolicyNumber(),
                claim.getClaimantName(),
                claim.getClaimType(),
                claim.getReason(),
                claim.getEstimatedAmount(),
                claim.getStatus(),
                claim.getSubmittedAt()
        );
    }

    private Claim findVisibleClaim(UUID claimId, PortalUser user) {
        if (user.getRole() == UserRole.AGENT) {
            return claimRepository.findById(claimId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        }
        return claimRepository.findByIdAndOwnerId(claimId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
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
