# Slice 6: Enterprise roles and workflow

Length: 6 to 8 days
Depends on: slice 4 gate, unless slice 5 was inserted and its gate is also required by the team. The source plan says no other slice starts before the previous gate. If slice 5 moved, the previous business gate is still slice 4.
UI: none in this slice. Screens are slice 7. The API must be complete enough for those screens.

## Work

- Role model for all eight roles with a permission check on every endpoint. Authority limits in configuration.
- Segregation of duties: no deciding on your own claim or your own policy's claim. The proposer of a rule change cannot approve it.
- ESCALATED flow: over-limit decisions route to a supervisor.
- UNDER_INVESTIGATION flow: SIU referral, findings, return to REVIEW_READY with a recommendation.
- Assignment, reassignment, due times, and SLA states, with a scheduled SLA evaluation.
- Internal notes and insured-visible messages as separate records.
- Settlement holds with reasons.
- Versioned rule configuration with a second-approver flow. Rule version recorded on every decision.
- Append-only audit trail and an access log for personal-data reveals and exports.
- Notifications table with read state.
- Agent book scoping from the synthetic agent-to-client links.
- Report queries: SLA, workload, ageing, and settlement reconciliation.
- Redis cache for policy, rule, and permission lookups with event-driven eviction.
- Distributed locks for scheduled jobs: SLA evaluation, outbox publisher, orphan cleanup.

## Gate

In API tests, each role can perform only its permitted actions. Over-limit and own-claim attempts are refused. A rule change needs two people. Every decision records its actor and rule version.

## Tests

- Adjuster above their authority limit is refused, and the claim routes to ESCALATED.
- A user acting on their own claim or own policy is denied and written to the audit trail.
- A rule change proposed and approved by the same person is refused.
- Auditor or Admin attempting a claim decision is refused.
- PII reveal and export require a reason and an access-log row (UI half is slice 7).
- SLA breach flags the claim for the queue and the supervisor dashboard query.
- A role or rule change while cached applies immediately through eviction.
