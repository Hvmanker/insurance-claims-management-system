# ADR-009: One claim state machine, with a human rejection gate

## Status

Accepted as a plan decision. The product changes are not built yet. Slice 0 confirms the enum against this list before migrating.

## Date

2026-10-11

## Context

Today's statuses are SUBMITTED, APPROVED, REJECTED, and SETTLED. High amounts wait in SUBMITTED. The README says they are rejected. The code does not auto-reject. v3 needs timeouts, analysis, and a human gate.

Business problem: every claim must be explainable as one path, including who is allowed to say no.

Technical constraints: the service that owns a transition is the only writer. Optimistic version. Audit row on every move. The source diagram was embedded and is not in the Markdown. The names below come from the prose.

Scalability: slice 6 adds ESCALATED and UNDER_INVESTIGATION without replacing the core path.

Security: only a human rejects. From slice 6, only inside authority, and never on their own claim or policy.

Cost: one batch job for expiry. No workflow engine license.

## Decision

Use the states the plan names: DRAFT, SUBMITTED, ANALYSIS_PENDING, REVIEW_READY, APPROVED, REJECTED, MORE_INFO_REQUESTED, WITHDRAWN, and EXPIRED, plus SIMULATED_SETTLED as the settlement terminal. REVIEW_READY is the human gate. MORE_INFO_REQUESTED returns to ANALYSIS_PENDING when documents arrive, or the batch job expires it. Map old statuses with an additive Flyway migration.

Slice 6 adds ESCALATED and UNDER_INVESTIGATION as branches from REVIEW_READY. Investigation cannot auto-reject. A settlement hold blocks SIMULATED_SETTLED until release.

## Alternatives Considered

### Keep the four statuses

- Pros: no migration.
- Cons: no place for analysis failure, more-info, or expiry, and rejection stays ambiguous.
- Rejected.

### A third-party BPM engine

- Pros: diagrams.
- Cons: another product on a 12 GB host, and the rules would leave the Java service.
- Rejected: the table in approval-service is the engine.

## Consequences

- Positive: the demo can show review, expiry, and simulated settlement as different outcomes.
- Risks: the embedded diagram might count SIMULATED_SETTLED inside "nine states". Slice 0 records the final enum and does not invent names.
- Maintenance: every new transition needs an owner, an audit row, and a test.
- Scaling: the read model in claim-service is eventually consistent. The UI may show "updating".

## Implementation Plan

- Folder impact: `ClaimStatus`, Flyway, approval-service, settlement-service, read model.
- Deployment: additive migration only.
- Testing: high-value claim stays in REVIEW_READY; expiry; 409 on concurrent decisions.
- Rollback: additive columns and new values can remain unused if a slice stops. Do not delete old rows.

## Teaching Section

### Architecture in simple terms

A claim is a token on a board. Only one team may move it from each square. The review square is where a person must act before anyone can say no.

### Business value

Auto-approval is narrow and audited. Everything uncertain waits for a human. That is the control an insurer expects to see.

### Long-term impact

Supervisors and investigators plug into the same board in slice 6.

### Best practices

Coded reasons. Version column. No service updates another service's table.

### Future optimization

SLA clocks sit on REVIEW_READY. They do not add a second state machine.
