# ADR-002: Auto damage claims as the product domain

## Status

Accepted as a plan decision. The product changes are not built yet.

## Date

2026-10-11

## Context

The repository accepts Auto and Home claim types. An earlier plan drifted into medical invoices. v3 needs one product a reviewer can understand, with synthetic repair estimates the analysis worker can be scored against.

Business problem: a demo that tries to be every line of insurance teaches none of them.

Technical constraints: claim type still exists on the row. The workflow, documents, and evaluation set are auto repair estimates.

Scalability: home and medical can be later products. They should not share this state machine by accident.

Security: synthetic aliases only. No real customer data, which also keeps free-tier model terms usable.

Cost: one evaluation set instead of three domains of fixtures.

## Decision

v3 is auto damage claims with repair estimates. Home and medical are later, separate products.

## Alternatives Considered

### Keep Auto and Home in one wizard

- Pros: matches the current form.
- Cons: two document shapes and a diluted evaluation set.
- Rejected: the worker's credibility depends on one fixture family.

### Medical invoices

- Pros: a previous draft already sketched them.
- Cons: that was scope drift, and the data is more sensitive.
- Rejected: out of scope for v1.

## Consequences

- Positive: policies, documents, and the evaluation set have one shape.
- Risks: the current UI still offers Home. Slice 1's wizard should not keep that path.
- Maintenance: claim-type configuration can grow later without putting home rules in this engine.
- Scaling: a second product can reuse outbox, roles, and storage patterns.

## Implementation Plan

- Folder impact: claim creation, seeds, and the fixture generator.
- Deployment: none specific.
- Testing: evaluation fixtures are repair estimates, including hostile text.
- Rollback: domain choice is documentation until slice 1's wizard lands.

## Teaching Section

### Architecture in simple terms

One claim story: a damaged car, a repair estimate, a rule, a human.

### Business value

Investors and reviewers can follow a single path from upload to simulated settlement.

### Long-term impact

The platform can add products later. v1 does not pretend to be a suite.

### Best practices

Say no to adjacent domains in the same slice. Put them on the non-goals list.

### Future optimization

A claim-type configuration can drive required documents when a second product exists. That configuration is already a UI requirement, with auto as the only type that ships.
