# ADR-009: One claim state machine, with a human rejection gate

## Status

Accepted as a plan decision. The product changes are not built yet. Slice 0 confirms the enum against this list before migrating.

## Date

2026-10-11

## Context

Today's statuses are SUBMITTED, APPROVED, REJECTED, and SETTLED. High amounts wait in SUBMITTED. The README says they are rejected. The code does not auto-reject. v3 needs timeouts, analysis, and a human gate.

Business problem: every claim must be explainable as one path, including who is allowed to say no.

Technical constraints: the service that owns a transition is the only writer. Optimistic version. Audit row on every move. The source diagram was embedded and is not in the Markdown. The names below come from the prose.

Scalability: slice 6 adds `ESCALATED` and `UNDER_INVESTIGATION` as extensions from `REVIEW_READY` without replacing the core path.

Security: only a human rejects. From slice 6, only inside authority (see ADR-018), and never on their own claim or policy.

Cost: one batch job for expiry. No workflow engine license.

## Decision

### Core enum (nine states)

The v3 **core** claim lifecycle uses **nine** states. **`SIMULATED_SETTLED` is one of the nine** (the settlement terminal shown in the read model). Claimant-initiated withdrawal is **out of scope** for the v3 enum.

| # | State | Role in the machine |
| --- | --- | --- |
| 1 | DRAFT | Started, not submitted. |
| 2 | SUBMITTED | Submitted; idempotency applies. |
| 3 | ANALYSIS_PENDING | Waiting for the analysis worker. |
| 4 | REVIEW_READY | Human gate. Uncertain or over-threshold paths stop here. |
| 5 | APPROVED | Rules passed or a human approved within authority. |
| 6 | REJECTED | Human rejected with a coded reason. |
| 7 | MORE_INFO_REQUESTED | Human requested documents; may return to analysis or expire. |
| 8 | EXPIRED | Batch job expired a stale `MORE_INFO_REQUESTED` claim. |
| 9 | SIMULATED_SETTLED | Settlement terminal (simulated; no money movement). |

`REVIEW_READY` is the human gate. `MORE_INFO_REQUESTED` returns to `ANALYSIS_PENDING` when documents arrive, or the batch job moves it to `EXPIRED`. Settlement-service owns the transition from `APPROVED` to `SIMULATED_SETTLED`. Map old statuses with an additive Flyway migration.

### Slice 6 additions (not part of the nine)

Slice **6A** adds **`ESCALATED`** when a decision exceeds authority (ADR-018); only a supervisor resolves escalation.

Slice **6B** adds **`UNDER_INVESTIGATION`** for SIU workflow. Investigation cannot auto-reject. Findings return the claim to `REVIEW_READY`. A settlement hold blocks `SIMULATED_SETTLED` until release.

## Alternatives Considered

### Keep the four statuses

- Pros: no migration.
- Cons: no place for analysis failure, more-info, or expiry, and rejection stays ambiguous.
- Rejected.

### Include a claimant-withdrawal terminal state

- Pros: models claimant cancellation.
- Cons: dropped from Himank's nine-state model; expiry and more-info cover operational exits.
- Rejected for v3.

### A third-party BPM engine

- Pros: diagrams.
- Cons: another product on a 12 GB host, and the rules would leave the Java service.
- Rejected: the table in approval-service is the engine.

## Consequences

- Positive: the demo can show review, expiry, and simulated settlement as different outcomes.
- Risks: slice 6 states must be migrated additively after the nine-state core lands in slice 1.
- Maintenance: every new transition needs an owner, an audit row, and a test.
- Scaling: the read model in claim-service is eventually consistent. The UI may show "updating".

## Implementation Plan

- Folder impact: `ClaimStatus`, Flyway, approval-service, settlement-service, read model.
- Deployment: additive migration only; slice 6 migrations add `ESCALATED` and `UNDER_INVESTIGATION`.
- Testing: high-value claim stays in `REVIEW_READY`; expiry; 409 on concurrent decisions; escalation in slice 6.
- Rollback: additive columns and new values can remain unused if a slice stops. Do not delete old rows.

## Teaching Section

### Architecture in simple terms

A claim is a token on a board. Only one team may move it from each square. The review square is where a person must act before anyone can say no.

### Business value

Auto-approval is narrow and audited. Everything uncertain waits for a human. That is the control an insurer expects to see.

### Long-term impact

Supervisors and investigators plug into the same board in slice 6 via `ESCALATED` and `UNDER_INVESTIGATION`.

### Best practices

Coded reasons. Version column. No service updates another service's table.

### Future optimization

SLA clocks sit on `REVIEW_READY`. They do not add a second state machine.
