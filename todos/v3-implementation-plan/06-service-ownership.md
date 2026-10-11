# Service ownership and enforcement

Date: 2026-10-11
Status: accepted plan

No service writes another service's tables. Each service owns a schema in one PostgreSQL instance. The public read model lives in claim-service and is updated by consuming events.

**No service changes a state it does not own.** Events may cross service boundaries, but only the owning service performs the transition and writes its audit row.

## Who owns which transition

| Service | Owns |
| --- | --- |
| claim-service | **DRAFT → SUBMITTED** and the **public read model** (updated via inbox). When the insured supplies documents, claim-service **publishes `claim.info.provided`** only; it does **not** move `MORE_INFO_REQUESTED` itself. |
| approval-service | Every transition from **ANALYSIS_PENDING** onward: through **APPROVED**, **REJECTED**, and **MORE_INFO_REQUESTED**, including **MORE_INFO_REQUESTED → ANALYSIS_PENDING** (after consuming `claim.info.provided`) and **MORE_INFO_REQUESTED → EXPIRED** (slice 2 simple scheduled job; slice 5 repackages as Spring Batch per ADR-012). After the owned transition, approval-service emits **`claim.analysis.requested`** when returning to analysis. **Authority limit enforcement** (ADR-018) lives here from slice 6A. From slice 6, also **ESCALATED**, **UNDER_INVESTIGATION**, assignment, and SLA. |
| settlement-service | APPROVED to SIMULATED_SETTLED. A record only. From slice 6, settlement holds block that transition until release. |
| notification-service | Delivery. It publishes to SNS. An SQS queue with its own DLQ performs outbound retries. It does not change claim state. |
| analysis worker | Reads READY documents and publishes facts. It does not write claim, approval, or settlement tables. |

## Rules that apply to every transition

- Write an audit row: actor, from, to, reason code, version, correlation id.
- Decisions carry an optimistic `version`. The second concurrent action gets HTTP 409.
- Submit requires an `Idempotency-Key` header so a double click or retry cannot create two submissions. The database unique constraint is the real guard. Redis is only a fast path from slice 4.
- Existing status values are mapped by an additive Flyway migration. Confirm current values in slice 0. They are `SUBMITTED`, `APPROVED`, `REJECTED`, and `SETTLED` as of this writing.

## Read model

Claim-service is what the UI reads. Approval and settlement publish `claim.decided` and `settlement.simulated`. Claim-service applies those events to its read model through the inbox. The UI can show an "updating" state because this path is eventually consistent.

## Why the split stays

The repo already has these four processes. Collapsing them would hide the event and ownership story the demo exists to show. Merging the quietest JVMs is allowed only as a last resort if slice 1 measurements show the 12 GB budget cannot hold them. See [20-hosting-memory-and-public-demo.md](20-hosting-memory-and-public-demo.md).

## ADR

[ADR-011](../architecture-decisions/ADR-011-schema-per-service-postgres.md)
