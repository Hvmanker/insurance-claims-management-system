# Decision table and thresholds

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-004](../architecture-decisions/ADR-004-java-rules-decide.md)

Rules run in order inside approval-service. The first matching rule wins. The worker never decides.

| # | Condition | Outcome | Actor |
| --- | --- | --- | --- |
| 1 | Analysis failed, timed out, or returned invalid output | REVIEW_READY, reason ANALYSIS_FAILED | SYSTEM |
| 2 | Policy not valid on the date of loss, or a duplicate claim or invoice number is detected | REVIEW_READY, with the flag | SYSTEM |
| 3 | Any worker flag, confidence below threshold, incomplete documents, or extracted amount outside tolerance of the claimed amount | REVIEW_READY, with the reasons | SYSTEM |
| 4 | Amount above AUTO_APPROVAL_LIMIT (config; the repo's current value is 10,000) | REVIEW_READY. Never a rejection. | SYSTEM |
| 5 | None of the above | APPROVED, with an audit entry listing the rules that passed | SYSTEM |

## Thresholds

Confidence and tolerance are starting values. They live in configuration, not in code. Slice 3 tunes them against the evaluation set. Slice 6 makes rule configuration versioned: an admin proposes a change and a second person approves it. Every decision stores the rule version it used.

`AUTO_APPROVAL_LIMIT` already exists as `claims.auto-approval-limit` with default `10000.00`. Keep it in config. Change the outcome above the limit from "leave it for the agent" to `REVIEW_READY`.

## What humans can do

On any `REVIEW_READY` claim, an adjuster can:

- approve, within their authority limit (slice 6),
- reject with a coded reason,
- request more information.

A supervisor approves or rejects escalated claims. An SIU investigator cannot approve, reject, or settle. Finance cannot change the claim decision. Admin cannot decide claims.

## What the worker must not send

The `claim.analysis.completed` payload has no `AUTO_APPROVE` field and no authoritative `risk_score`. Flags such as `AMOUNT_MISMATCH` or `SUSPECTED_INSTRUCTION_IN_DOCUMENT` are inputs to rule 3. They are not decisions.

If output is invalid, late, or missing, rule 1 routes the claim to `REVIEW_READY`.

## Current repo

`ApprovalService.evaluate` approves when the estimated amount is less than or equal to the limit and otherwise returns without writing a rejection. Manual decisions arrive on `claim.manual-decision` and can approve or reject with a free-text reason. There is no ordered safety table, no policy-date check, no document completeness check, and no rule version.

The README sentence that says higher-value claims are rejected does not match this method. The decision table replaces both the code path and that sentence.

## Tests

Slice 1 covers the high-value claim waiting in `REVIEW_READY` and the inability to settle without approval. Slice 3 covers malformed documents, embedded instructions, and missing or invalid LLM output. Slice 6 covers authority limits, own-claim denial, and two-person rule changes. See [32-test-matrix.md](32-test-matrix.md).
