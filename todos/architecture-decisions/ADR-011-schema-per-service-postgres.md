# ADR-011: One PostgreSQL instance, one schema per service

## Status

Accepted as a plan decision. This matches the current repo and stays. The product features on top are not built yet.

## Date

2026-10-11

## Context

Claim, approval, and settlement already use schemas in one database, each with its own Flyway history. Notification-service does not own a schema today. A production note in the README says the schemas can be split into databases later.

Business problem: show clear ownership without operating four databases on a laptop.

Technical constraints: no service writes another service's tables. Approval-service uses the policy snapshot on the event and does not query the claim schema.

Scalability: one instance is the demo. Aurora is the AWS target and is not created (EX-005).

Security: database credentials stay in the environment. Slice 0 must not add cross-schema grants for convenience.

Cost: about 1 GB for PostgreSQL.

## Decision

Keep one PostgreSQL instance and a schema per owning service. Claim-service owns policies, documents, and the public read model. Approval-service owns decisions, assignment, and rule versions. Settlement-service owns simulated settlements and holds. Cross-service reads of operational state go through events.

## Alternatives Considered

### Separate databases now

- Pros: stronger isolation.
- Cons: more Compose and backup surface for the same rule.
- Rejected for the demo. The boundary is the schema and the code, which is enough to enforce.

### Shared tables

- Pros: simpler joins for the UI.
- Cons: hidden coupling. The read model exists so the UI does not need those joins.
- Rejected.

## Consequences

- Positive: Flyway migrations stay independent. A claim migration cannot silently alter approvals.
- Risks: someone will be tempted to join across schemas. Reviews should reject that.
- Maintenance: the read model can lag. The UI shows "updating".
- Scaling: splitting schemas into databases later does not change the event contracts.

## Implementation Plan

- Folder impact: existing Flyway locations. New tables land in the owning service.
- Deployment: current Compose Postgres.
- Testing: Testcontainers Postgres for integration tests. No test should query two schemas to make a decision.
- Rollback: additive migrations. Old status values are mapped, not dropped in slice 1.

## Teaching Section

### Architecture in simple terms

One building, locked rooms. Each team has a key to its own room. They pass notes in the hallway.

### Business value

Ownership is auditable. When a decision is wrong, it is approval-service's row and approval-service's rule version.

### Long-term impact

Aurora can host the same split, or separate clusters, without a new domain model.

### Best practices

Snapshot on the event for the few fields another service must know. Expire snapshots by versioning the contract, not by reaching into the other schema.

### Future optimization

Read replicas are unnecessary on the demo. Add them only after a measured read bottleneck.
