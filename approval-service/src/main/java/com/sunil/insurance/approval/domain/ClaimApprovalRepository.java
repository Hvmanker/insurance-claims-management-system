package com.sunil.insurance.approval.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClaimApprovalRepository extends JpaRepository<ClaimApproval, UUID> {
}

