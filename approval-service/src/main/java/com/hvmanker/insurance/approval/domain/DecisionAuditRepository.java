package com.hvmanker.insurance.approval.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DecisionAuditRepository extends JpaRepository<DecisionAudit, UUID> {
}
