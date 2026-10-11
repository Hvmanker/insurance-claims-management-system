# Domain state machine

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-009](../architecture-decisions/ADR-009-claim-state-machine.md)

Every claim follows one state machine. The service that owns a transition is the only service that performs it. The rule engine may auto-approve only when every safety condition holds. Rejection is always a human action.

The source plan's diagram is embedded and was not transcribed into the Markdown. The states below are the ones the prose names. Slice 0 should confirm this list before the Flyway migration. Do not add states the plan does not name.

## Core states

The plan describes nine states and one human gate. The names used by the ownership section and the decision table are:

| State | Meaning |
| --- | --- |
| DRAFT | Claim started, not yet submitted. |
| SUBMITTED | Insured or agent submitted. Idempotency applies here. |
| ANALYSIS_PENDING | Waiting for the analysis worker. |
| REVIEW_READY | Human gate. Every path except all five rules passing ends here. |
| APPROVED | Rules passed, or a human approved inside their authority. |
| REJECTED | A human rejected with a coded reason. |
| MORE_INFO_REQUESTED | A human asked for more documents. |
| WITHDRAWN | Claimant withdrew. Owned by claim-service. |
| EXPIRED | Batch job expired a stale MORE_INFO_REQUESTED claim. |

`SIMULATED_SETTLED` is the settlement terminal. Settlement-service owns the move from `APPROVED` to `SIMULATED_SETTLED`. The UI labels it simulated. No money moves. Treat it as part of the claim lifecycle the read model must show. If the embedded diagram counts it inside the nine, slice 0 records that and does not invent an extra name.

Slice 6 adds two branches from `REVIEW_READY`. They extend the machine:

- `ESCALATED` when an adjuster's decision exceeds their authority limit. A supervisor approves or rejects.
- `UNDER_INVESTIGATION` when a fraud flag crosses a threshold or an adjuster refers the claim. An SIU investigator records findings. The claim returns to `REVIEW_READY` with a recommendation. Investigation never auto-rejects.

## Transitions the plan specifies

- Submit moves the claim out of draft into the analysis path and emits `claim.submitted`.
- Analysis completion is consumed by approval-service, which applies the decision table.
- `MORE_INFO_REQUESTED` returns to `ANALYSIS_PENDING` when documents arrive.
- A stale `MORE_INFO_REQUESTED` claim becomes `EXPIRED` through the Spring Batch job.
- `REVIEW_READY` is the only place a human approve, reject, or request-more-info starts, until slice 6 adds escalation and investigation.
- Settlement does not run unless the claim is approved, and a hold placed in slice 6 blocks `SIMULATED_SETTLED` until release.

## Human gate

`REVIEW_READY` is the gate. System paths that fail any safety rule stop there with a reason code. An adjuster may approve, reject with a coded reason, or request more information. Only a human can reject. In slice 6 that human must also be inside their authority limit, and they cannot decide a claim they submitted or a claim on their own policy.

## Audit

Every transition writes an audit row: actor, from, to, reason code, version, and correlation id. Decisions carry an optimistic `version`. A concurrent second action receives HTTP 409.

## Current repo

Today the enum is `SUBMITTED`, `APPROVED`, `REJECTED`, `SETTLED`. There is no draft, no analysis wait, no review gate, no expiry, and no simulated label. High-value claims sit in `SUBMITTED` until an agent decides. Map old values with an additive Flyway migration in slice 1. Confirm the current values again in slice 0.

## Business value

A single machine makes the demo explainable. Reviewers can point at one gate and show that a document, a model, or a large amount cannot force payment. The extra enterprise states keep that story when supervisors and investigators join.
