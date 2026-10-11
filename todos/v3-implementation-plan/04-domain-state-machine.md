# Domain state machine

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-009](../architecture-decisions/ADR-009-claim-state-machine.md)

Every claim follows one state machine. The service that owns a transition is the only service that performs it. The rule engine may auto-approve only when every safety condition holds. Rejection is always a human action.

The source plan's diagram is embedded and was not transcribed into the Markdown. The states below are the ones the prose names. Slice 0 should confirm this list before the Flyway migration. Do not add states the plan does not name.

## Core states (nine)

Slice 0 locks the **nine** core states below. **`SIMULATED_SETTLED` is the ninth** (settlement terminal in the read model). Claimant withdrawal is not a named state in v3. `REVIEW_READY` is the human gate.

| State | Meaning |
| --- | --- |
| DRAFT | Claim started, not yet submitted. |
| SUBMITTED | Insured or broker submitted. Idempotency applies here. |
| ANALYSIS_PENDING | Waiting for the analysis worker. |
| REVIEW_READY | Human gate. Every path except all five rules passing ends here. |
| APPROVED | Rules passed, or a human approved inside their authority. |
| REJECTED | A human rejected with a coded reason. |
| MORE_INFO_REQUESTED | A human asked for more documents. |
| EXPIRED | approval-service expired a stale `MORE_INFO_REQUESTED` claim (slice 2 scheduled job; Spring Batch from slice 5). |
| SIMULATED_SETTLED | Settlement terminal. Settlement-service owns the move from `APPROVED`. The UI labels it simulated. No money moves. |

### Slice 6 extensions (not counted in the nine)

Slice **6A** adds **`ESCALATED`** when a decision exceeds authority (ADR-018). Only a supervisor decides escalated claims.

Slice **6B** adds **`UNDER_INVESTIGATION`** when a fraud flag crosses a threshold or an adjuster refers the claim. An SIU investigator records findings. The claim returns to `REVIEW_READY` with a recommendation. Investigation never auto-rejects.

## Transitions the plan specifies

- Submit moves the claim out of draft into the analysis path and emits `claim.submitted`.
- Analysis completion is consumed by approval-service, which applies the decision table.
- **More information (approval-service owns the transitions):** when documents arrive, claim-service publishes **`claim.info.provided`**. **approval-service** then moves **`MORE_INFO_REQUESTED` → `ANALYSIS_PENDING`** and only then emits **`claim.analysis.requested`**.
- A stale **`MORE_INFO_REQUESTED`** claim becomes **`EXPIRED`** through a **slice 2** simple scheduled job in approval-service; **slice 5** repackages that job as Spring Batch (ADR-012).
- **`REVIEW_READY`** is where human approve and reject start in slice 1. **Request-more-info** lands in **slice 2** with documents. Slice 6 adds escalation and investigation.
- Settlement does not run unless the claim is approved, and a hold placed in slice 6 blocks `SIMULATED_SETTLED` until release.

## Human gate

`REVIEW_READY` is the gate. System paths that fail any safety rule stop there with a reason code. In slice 1 an adjuster may approve or reject with a coded reason; request-more-info arrives in slice 2. Only a human can reject. From slice 6, approve and reject must be inside authority limits (ADR-018); request-more-info is not limit-gated. Nobody can decide a claim they submitted or a claim on their own policy.

## Audit

Every transition writes an audit row: actor, from, to, reason code, version, and correlation id. Decisions carry an optimistic `version`. A concurrent second action receives HTTP 409.

## Current repo

Today the enum is `SUBMITTED`, `APPROVED`, `REJECTED`, `SETTLED`. There is no draft, no analysis wait, no review gate, no expiry, and no simulated label. High-value claims sit in `SUBMITTED` until an agent decides. Map old values with an additive Flyway migration in slice 1. Confirm the current values again in slice 0.

## Business value

A single machine makes the demo explainable. Reviewers can point at one gate and show that a document, a model, or a large amount cannot force payment. The extra enterprise states keep that story when supervisors and investigators join.
