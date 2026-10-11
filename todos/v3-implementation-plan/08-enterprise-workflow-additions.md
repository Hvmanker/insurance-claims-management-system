# Enterprise workflow additions

Date: 2026-10-11
Status: accepted plan, built in slice 6
ADR: [ADR-015](../architecture-decisions/ADR-015-eight-role-model-and-segregation-of-duties.md)

The core path serves the insured user and one adjuster. Slice 6 adds the operations around that path. These additions extend the state machine. They do not replace it.

| Addition | Trigger | Owner | Result |
| --- | --- | --- | --- |
| ESCALATED | An adjuster's decision exceeds their authority limit | approval-service | A supervisor approves or rejects. The adjuster cannot. |
| UNDER_INVESTIGATION | Fraud flag over threshold, or an adjuster refers the claim | approval-service | An SIU investigator records findings, then the claim returns to REVIEW_READY with a recommendation. Never an automatic rejection. |
| Assignment and SLA | A claim enters REVIEW_READY | approval-service | An owner, a due time, and an SLA state: on track, at risk, or breached. A supervisor can reassign. |
| Settlement hold | A supervisor or finance officer places a hold | settlement-service | SIMULATED_SETTLED is blocked until the hold is released, with a reason. SIU can request a hold. |
| Rule versions | An admin proposes a threshold change | approval-service | A second person approves it. Every decision records the rule version it used. |

## Segregation of duties

- Nobody decides a claim they submitted.
- Nobody decides a claim on their own policy.
- The person who proposes a rule change cannot approve it.
- Auditors have read-only access.
- Admins manage users, roles, policy data, and rule thresholds. They cannot decide claims.
- SIU cannot approve, reject, or settle.
- Finance cannot change claim decisions.

Denied attempts are written to the audit trail.

## Notes and messages

Internal claim notes and insured-visible messages are separate records. A note is not a message to the customer.

## Access log

Revealing a masked personal field, and exporting a report, each require a reason and an access-log row. The audit table is append-only.

## Cache and locks in this slice

Policy, rule, and permission lookups may be cached. A role change or a new rule version evicts the affected keys immediately. Scheduled jobs (SLA evaluation, outbox publisher, orphan cleanup) take a one-runner lock. If Redis is down, the job skips a cycle or uses a database lock. Locks are never the guard for a decision or a settlement.

## Gate

API tests show each role can perform only its permitted actions, over-limit and own-claim attempts are refused, a rule change needs two people, and every decision records its actor and rule version.

## Cut order

Supervisor, SIU, Auditor, and Admin workspaces are P1. Finance and Agent workspaces are the first P2 cuts. The API rules for those roles should still exist if the screens are cut, or the cut must be explicit in the slice 6 gate notes. The P0 item is role-based access control with an audit trail, which slice 1 starts for Insured and Adjuster.
