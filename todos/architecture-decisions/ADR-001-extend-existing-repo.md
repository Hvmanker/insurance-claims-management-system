# ADR-001: Extend the existing repository

## Status

Accepted as a plan decision. The product changes are not built yet.

## Date

2026-10-11

## Context

The insurance claims repository already runs four Spring Boot services, PostgreSQL, Kafka, Redis cache, and a small portal. A rewrite would take one to two weeks and discard a working baseline.

**Owner permission:** pending confirmation by **Hvmanker**.

**Original work:** The baseline implementation was authored by **sunilguntupalli** ([`sunilguntupalli/insurance-claims-management-system`](https://github.com/sunilguntupalli/insurance-claims-management-system) on GitHub), as reflected in the prior README CI badge and `com.sunil.insurance` package namespace. Slice 0.9 credits that upstream project in this repo's README.

Business problem: ship one focused auto-claims demo without spending the schedule on a new codebase.

Technical constraints: keep the four service boundaries. Record the permission in the README during slice 0.

Scalability: the current single-database, schema-per-service layout remains. A later production split into databases is possible because services already avoid each other's tables.

Security: existing session and BCrypt code stays, with the gaps in ADR-003 fixed rather than replaced.

Cost: avoiding a rewrite is the main saving. No new vendor.

## Decision

Extend this repository. Do not start a parallel codebase. Slice 0.9 documents owner permission status and upstream author credit in the README and this ADR, and audits current behavior before feature work.

## Alternatives Considered

### Rewrite

- Pros: clean module layout, no legacy status enum.
- Cons: one to two weeks before the first claims demo, and a second system to keep running.
- Rejected: the baseline already demonstrates the service split.

### Fork without recording permission

- Pros: faster locally.
- Cons: the demo's story depends on a truthful credit and permission line.
- Rejected: slice 0 makes the permission explicit.

## Consequences

- Positive: slice 1 starts from Compose, Flyway, and Kafka that already run.
- Risks: hidden defects. Slice 0 audits logs, roles, ports, and status values first.
- Maintenance: old topic names and the four-status enum must be migrated, not ignored.
- Scaling: service boundaries stay, so a later split does not require a rewrite.

## Implementation Plan

- Folder impact: all four services, `common`, README, and `todos/`.
- Deployment: unchanged in slice 0. Compose remains the local entry.
- Testing: run the existing tests and Compose from a clean clone.
- Rollback: slice 0 is documentation and README corrections. Feature slices revert independently.

## Teaching Section

### Architecture in simple terms

Keep the house and add rooms. Claim, approval, settlement, and notification stay separate processes.

### Business value

Reviewers see a real system being improved, including its current mistakes, such as the README port mismatch and the agent role that can decide every claim.

### Long-term impact

Future products (home, medical) can be separate repos or modules. This repo stays the auto-claims product.

### Best practices

Write down verified behavior before changing it. Credit the original author in the README (done in slice 0.9).

### Future optimization

If memory forces a merge of quiet services, that is a last resort after slice 1 measurements, not a reason to rewrite now.
