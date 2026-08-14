package com.sunil.insurance.claims.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PortalUserRepository extends JpaRepository<PortalUser, UUID> {
    Optional<PortalUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
