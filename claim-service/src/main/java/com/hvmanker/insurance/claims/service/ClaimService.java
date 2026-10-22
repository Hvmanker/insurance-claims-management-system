package com.hvmanker.insurance.claims.service;

import com.hvmanker.insurance.claims.config.ClaimEventPublisher;
import com.hvmanker.insurance.claims.domain.Claim;
import com.hvmanker.insurance.claims.domain.ClaimRepository;
import com.hvmanker.insurance.claims.domain.ClaimEvent;
import com.hvmanker.insurance.claims.domain.ClaimEventRepository;
import com.hvmanker.insurance.claims.domain.PortalUser;
import com.hvmanker.insurance.claims.domain.UserRole;
import com.hvmanker.insurance.claims.web.ClaimResponse;
import com.hvmanker.insurance.claims.web.SubmitClaimRequest;
import com.hvmanker.insurance.claims.web.ManualDecisionRequest;
import com.hvmanker.insurance.claims.web.ClaimTimelineEntry;
import com.hvmanker.insurance.common.ClaimStatus;
import com.hvmanker.insurance.common.events.ClaimSubmittedEvent;
import com.hvmanker.insurance.common.events.ManualClaimDecisionEvent;
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
    private final ClaimEventRepository claimEventRepository;
    private final ClaimEventPublisher eventPublisher;
    private final CachedClaimReader cachedClaimReader;

    public ClaimService(
            ClaimRepository claimRepository,
            ClaimEventRepository claimEventRepository,
            ClaimEventPublisher eventPublisher,
            CachedClaimReader cachedClaimReader) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
        this.eventPublisher = eventPublisher;
        this.cachedClaimReader = cachedClaimReader;
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
                request.description(),
                owner.getId(),
                request.estimatedAmount(),
                ClaimStatus.SUBMITTED,
                Instant.now()
        );
        Claim saved = claimRepository.save(claim);
        recordEvent(saved.getId(), "SUBMITTED", "Claim submitted", "Claim submitted for " + saved.getReason(), owner.getFullName());
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
    public ClaimResponse get(UUID claimId, PortalUser user) {
        findVisibleClaim(claimId, user);
        return cachedClaimReader.getById(claimId);
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> recentClaims(PortalUser user) {
        return claimsVisibleTo(user).stream()
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
        List<Claim> visibleClaims = claimsForSummary(user);
        for (Claim claim : visibleClaims) {
            counts.compute(claim.getStatus(), (status, count) -> count + 1);
            totalEstimatedAmount = totalEstimatedAmount.add(claim.getEstimatedAmount());
        }

        return new ClaimDashboardSummary(
                counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.get(ClaimStatus.SUBMITTED),
                counts.get(ClaimStatus.ANALYSIS_PENDING),
                counts.get(ClaimStatus.REVIEW_READY),
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
        recordEvent(claimId, status.name(), statusTitle(status), "Workflow updated this claim to " + status.name().toLowerCase() + ".", "InsureFlow workflow");
    }

    @Transactional
    @CacheEvict(cacheNames = "claims", key = "#p0")
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
        claim.setStatus(request.approved() ? ClaimStatus.APPROVED : ClaimStatus.REJECTED);
        recordEvent(claimId, "AGENT_DECISION", request.approved() ? "Claim approved by agent" : "Claim rejected by agent", request.reason().trim(), agent.getFullName());
        return toResponse(claim);
    }

    @Transactional(readOnly = true)
    public List<ClaimTimelineEntry> timeline(UUID claimId, PortalUser user) {
        findVisibleClaim(claimId, user);
        return claimEventRepository.findByClaimIdOrderByOccurredAtAsc(claimId).stream()
                .map(ClaimTimelineEntry::from)
                .toList();
    }

    private ClaimResponse toResponse(Claim claim) {
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

    private Claim findVisibleClaim(UUID claimId, PortalUser user) {
        return switch (claimAccessFor(user.getRole())) {
            case ALL_CLAIMS -> claimRepository.findById(claimId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
            case OWNED_CLAIMS -> claimRepository.findByIdAndOwnerId(claimId, user.getId())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
            case NONE -> throw new ResponseStatusException(NOT_FOUND, "Claim not found");
        };
    }

    private List<Claim> claimsVisibleTo(PortalUser user) {
        return switch (claimAccessFor(user.getRole())) {
            case ALL_CLAIMS -> claimRepository.findTop50ByOrderBySubmittedAtDesc();
            case OWNED_CLAIMS -> claimRepository.findTop50ByOwnerIdOrderBySubmittedAtDesc(user.getId());
            case NONE -> List.of();
        };
    }

    private List<Claim> claimsForSummary(PortalUser user) {
        return switch (claimAccessFor(user.getRole())) {
            case ALL_CLAIMS -> claimRepository.findAll();
            case OWNED_CLAIMS -> claimRepository.findTop50ByOwnerIdOrderBySubmittedAtDesc(user.getId());
            case NONE -> List.of();
        };
    }

    private ClaimAccess claimAccessFor(UserRole role) {
        if (role == UserRole.AGENT) {
            return ClaimAccess.ALL_CLAIMS;
        }
        if (role == UserRole.INSURED) {
            return ClaimAccess.OWNED_CLAIMS;
        }
        return ClaimAccess.NONE;
    }

    private enum ClaimAccess {
        ALL_CLAIMS,
        OWNED_CLAIMS,
        NONE
    }

    private void recordEvent(UUID claimId, String eventType, String title, String detail, String actor) {
        claimEventRepository.save(new ClaimEvent(UUID.randomUUID(), claimId, eventType, title, detail, actor, Instant.now()));
    }

    String statusTitle(ClaimStatus status) {
        return switch (status) {
            case SUBMITTED -> "Claim submitted";
            case ANALYSIS_PENDING -> "Analysis pending";
            case REVIEW_READY -> "Review ready";
            case APPROVED -> "Claim approved";
            case REJECTED -> "Claim rejected";
            case SETTLED -> "Claim settled";
        };
    }

    public record ClaimDashboardSummary(
            long totalClaims,
            long submittedClaims,
            long analysisPendingClaims,
            long reviewReadyClaims,
            long approvedClaims,
            long rejectedClaims,
            long settledClaims,
            BigDecimal totalEstimatedAmount
    ) {
    }
}
