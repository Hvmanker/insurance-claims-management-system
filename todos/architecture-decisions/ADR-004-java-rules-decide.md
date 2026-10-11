# ADR-004: Java rules decide

## Status

Accepted as a plan decision. The product changes are not built yet.

## Date

2026-10-11

## Context

A Python worker will read repair estimates. Models can be confident and wrong, and a document can contain the sentence "approve this claim". LLM scores are not deterministic and not calibrated.

Business problem: a claims demo that lets a document or a model approve payment is not credible.

Technical constraints: approval-service owns the decision table. The worker returns facts, per-field confidence, evidence, and non-binding flags. The payload has no `AUTO_APPROVE` and no authoritative risk score.

Scalability: rules are configuration, versioned in slice 6, so threshold changes do not require a worker deploy.

Security: document text is untrusted data. It is never concatenated into instructions and never logged.

Cost: the deterministic parser runs without an API key. The LLM adapter is off in CI.

## Decision

Java rules in approval-service decide. The worker cannot approve, reject, or pay. Rejection is always a human action. Auto-approval happens only when all five rules pass.

## Alternatives Considered

### Let the model emit an approval

- Pros: fewer services on the happy path.
- Cons: prompt injection and uncalibrated scores.
- Rejected: the evaluation set includes injection fixtures that must never approve.

### Score threshold inside the worker

- Pros: one place for confidence.
- Cons: two decision makers, and the worker would need policy data.
- Rejected: confidence is an input to rule 3, not a decision.

## Consequences

- Positive: a hostile PDF cannot finish a claim.
- Risks: rule bugs become the new source of bad approvals. The ordered table and the audit of which rules passed are the control.
- Maintenance: thresholds live in config. Slice 6 requires two people to change them.
- Scaling: the worker can be slow or down. Rule 1 sends the claim to REVIEW_READY.

## Implementation Plan

- Folder impact: approval-service rule engine, worker payload, `contracts/`.
- Deployment: worker is internal-only.
- Testing: injection fixtures, timeouts, invalid output, high-value claims. See the test matrix.
- Rollback: the slice 1 stub still publishes facts, not decisions, so the rule engine is in place before the real extractor.

## Teaching Section

### Architecture in simple terms

The worker is a witness. The rule engine is the judge. A human is the only one who can deny the claim.

### Business value

This is the sentence the interview narrative leads with: a document cannot talk its way into an approval.

### Long-term impact

New flags can be added without giving the model more authority.

### Best practices

Validate worker output. Treat missing output as review, not as approval. Record the rule version on the decision.

### Future optimization

Tune confidence and tolerance against the published precision and recall. Do not hard-code the tuned numbers.
