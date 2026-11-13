package com.hvmanker.insurance.approval.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimApprovalRepository extends JpaRepository<ClaimApproval, UUID> {
    Optional<ClaimApproval> findByDecisionId(UUID decisionId);
}

