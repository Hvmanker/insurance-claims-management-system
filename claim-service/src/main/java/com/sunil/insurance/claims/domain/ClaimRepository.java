package com.sunil.insurance.claims.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    List<Claim> findTop50ByOrderBySubmittedAtDesc();

    List<Claim> findTop50ByOwnerIdOrderBySubmittedAtDesc(UUID ownerId);

    java.util.Optional<Claim> findByIdAndOwnerId(UUID id, UUID ownerId);
}
