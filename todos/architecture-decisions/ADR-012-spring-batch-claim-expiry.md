# ADR-012: Spring Batch expires stale information requests

## Status

Accepted as a plan decision. The job is slice 5. The product is not built yet.

## Date

2026-10-11

## Context

MORE_INFO_REQUESTED cannot wait forever. The guideline also asks for a batch workload. EventBridge and AWS Batch are a gap (EX-001).

Business problem: claims that are waiting on the customer need a visible timeout, or the queue lies.

Technical constraints: one containerized job. A cron container is the schedule. Real cron hardening is P2 and may be cut. The job itself stays.

Scalability: a distributed lock, from slice 6, or a database lock, stops two runners. The lock does not guard the decision. The state transition does.

Security: the job is an actor SYSTEM, with an audit row and a reason code.

Cost: a small container. No AWS Batch charges.

## Decision

Run one Spring Batch job that moves stale MORE_INFO_REQUESTED claims to EXPIRED. Claim-service owns that status. The job's schedule is a cron container. Do not claim EventBridge.

## Alternatives Considered

### Expire inside the request path

- Pros: no batch runtime.
- Cons: expiry only happens when someone happens to read the claim, and the guideline row stays empty.
- Rejected.

### AWS Batch now

- Pros: matches the guideline.
- Cons: account and cost. EX-001 records the gap.
- Rejected for the demo.

## Consequences

- Positive: the state machine has a real timeout, and the batch row is implemented.
- Risks: a wrong staleness window expires active claims. The window is config, and the test uses a clock that is already stale.
- Maintenance: if P2 cuts the polished scheduler, a simple container cron still has to run the job for the test.
- Scaling: one claim at a time is enough for the demo volume.

## Implementation Plan

- Folder impact: claim-service or a small batch module that calls claim-service's transition. It must not update approval tables directly.
- Deployment: slice 5 Compose service.
- Testing: a stale claim becomes EXPIRED. A fresh one does not.
- Rollback: disable the cron. Claims stay in MORE_INFO_REQUESTED.

## Teaching Section

### Architecture in simple terms

A nightly clerk closes files that have been waiting too long. The clerk writes the same kind of audit note a person would.

### Business value

The queue does not fill with forgotten requests, and the standards matrix has a real batch job.

### Long-term impact

The same job shape can move to AWS Batch later. The transition rule stays in claim-service.

### Best practices

Idempotent runs. Audit every expiry. Do not expire a claim that has already moved.

### Future optimization

A real scheduler and metrics on lag, after the P2 items above it are safe to keep.
