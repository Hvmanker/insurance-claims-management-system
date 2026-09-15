package com.sunil.insurance.claims.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimEventRepository extends JpaRepository<ClaimEvent, UUID> {
    List<ClaimEvent> findByClaimIdOrderByOccurredAtAsc(UUID claimId);
}
