package com.hvmanker.insurance.claims.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface PortalUserRepository extends JpaRepository<PortalUser, UUID> {
    Optional<PortalUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    default PortalUser upsertByEmail(PortalUser candidate) {
        String email = candidate.getEmail().toLowerCase(Locale.ROOT);
        return findByEmailIgnoreCase(email)
                .map(existing -> save(new PortalUser(
                        existing.getId(),
                        email,
                        candidate.getFullName(),
                        candidate.getPasswordHash(),
                        candidate.getRole(),
                        existing.getCreatedAt())))
                .orElseGet(() -> save(new PortalUser(
                        candidate.getId(),
                        email,
                        candidate.getFullName(),
                        candidate.getPasswordHash(),
                        candidate.getRole(),
                        candidate.getCreatedAt())));
    }
}
