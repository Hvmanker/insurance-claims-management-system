package com.hvmanker.insurance.approval.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public class ClaimWorkflowStore {
    private final JdbcTemplate jdbcTemplate;

    public ClaimWorkflowStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(UUID claimId, String status, String policyNumber, BigDecimal claimedAmount) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO claim_workflow (claim_id, status, policy_number, claimed_amount, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (claim_id) DO NOTHING
                """,
                claimId,
                status,
                policyNumber,
                claimedAmount);
        return inserted == 1;
    }

    public int transitionStatus(UUID claimId, String newStatus, List<String> allowedCurrentStatuses) {
        if (allowedCurrentStatuses.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", allowedCurrentStatuses.stream().map(s -> "?").toList());
        Object[] args = new Object[2 + allowedCurrentStatuses.size()];
        args[0] = newStatus;
        args[1] = claimId;
        for (int i = 0; i < allowedCurrentStatuses.size(); i++) {
            args[i + 2] = allowedCurrentStatuses.get(i);
        }
        return jdbcTemplate.update(
                "UPDATE claim_workflow SET status = ?, updated_at = now() WHERE claim_id = ? AND status IN ("
                        + placeholders
                        + ")",
                args);
    }
}
