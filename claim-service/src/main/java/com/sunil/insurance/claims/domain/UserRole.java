package com.sunil.insurance.claims.domain;

/**
 * Portal roles. {@link #AGENT} is the legacy decision role (maps to {@link #ADJUSTER} in Phase 1B).
 * {@link #BROKER} is the v3 Agent (broker book); UI label is "Agent".
 * {@link #ADJUSTER} is inert in Phase 0 until the 1B migration retires {@link #AGENT}.
 */
public enum UserRole {
    INSURED,
    /** Legacy claim-decision role; retained for demo until Phase 1B maps to ADJUSTER. */
    @Deprecated
    AGENT,
    BROKER,
    ADJUSTER,
    SUPERVISOR,
    SIU,
    FINANCE,
    AUDITOR,
    ADMIN
}
