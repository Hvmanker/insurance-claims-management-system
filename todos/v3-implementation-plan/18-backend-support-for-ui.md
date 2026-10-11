# Backend support for the UI

Date: 2026-10-11
Status: accepted plan

The screens in [17-ui-screens-and-dynamic-behavior.md](17-ui-screens-and-dynamic-behavior.md) depend on API behavior. The UI must not invent permissions the server does not enforce.

| Need | Backend support | Slice |
| --- | --- | --- |
| Roles, permissions, authority limits | Role claims in the session. A permission check on every endpoint. Per-role limits in configuration. | 1 and 6 |
| Assignment and SLA | Assignee, due time, and SLA state on review items. A reassign endpoint. A scheduled SLA evaluation. | 6 |
| Notes and messages | Internal claim notes and insured-visible messages as separate records. | 6 |
| Audit and access log | An append-only audit table. An access log for personal-data reveals and exports. | 1 and 6 |
| Notifications inbox | A notifications table with read state, and a feed for live updates. | 6 and 7 |
| Search and saved views | A role-scoped search endpoint. Stored saved views. | 6 and 7 |
| Rule configuration | Versioned rules, a propose-and-approve flow, and the rule version stored on every decision. | 6 |
| Reports | Queries for SLA, workload, ageing, and settlement reconciliation. | 6 and 7 |
| Live updates | A per-claim and per-queue event feed. | 7 |

## Slice 1 minimum

Insured and Adjuster role claims in the Redis-backed session. Permission checks on the endpoints those two roles use. Audit rows and optimistic version on decisions. The read model that the status page polls.

## Slice 6 minimum

The rest of the matrix, segregation of duties, escalation, investigation, holds, and the two-person rule change. Report queries exist even if a P2 cut drops a screen.

## Slice 7 minimum

The feed for live updates, saved views if the adjuster queue ships them, and the access-log write when a reveal or export happens from the UI.

## Consistency

URL state is a frontend concern. The server still scopes every query. A shared link must not widen what the role can see.

Eventual consistency is visible. The API can return the last committed read model while a decision is still on the outbox. The UI shows "updating" rather than a second write.
