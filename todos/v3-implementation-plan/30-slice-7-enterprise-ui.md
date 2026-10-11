# Slice 7: Enterprise UI by role

Length: 8 to 10 days
Depends on: slice 6 gate
UI: this slice is the UI

## Work

- App shell: design system, role-aware navigation, 403 and error pages, idle-timeout warning.
- Insured workspace: home with actions needed, wizard with draft autosave and upload progress, claim detail with tracker and timeline, information requests, notifications.
- Agent workspace: client book, claim on behalf of a client, status views.
- Adjuster workspace: queue with saved views and SLA badges, review console (evidence viewer, extracted fields, rule panel, decision form with coded reasons, escalate, refer to SIU, request information), internal notes.
- Supervisor workspace: team dashboard, escalation queue, reassign and bulk actions, search, exports.
- SIU workspace: referral queue, case view with findings, hold requests.
- Finance workspace: settlement queue, holds, reconciliation report.
- Auditor workspace: global search, audit and access-log views, read-only exports.
- Admin workspace: users and roles, policy data, rule configuration with approval, system health.
- Live updates fanned out through Redis pub/sub, with polling fallback.
- URL-based filters.
- Personal-data masking with reveal-and-log.
- Accessibility checks, responsive insured and agent screens, loading, empty, and error states.
- Playwright journeys for every role, plus forbidden-access tests.

## Gate

Every role completes its main journey in a browser after a refresh. Forbidden routes and actions are denied with clear messages. An auditor can trace any decision to its actor, time, rule version, and evidence. The axe checks pass.

## Tests

- A role that opens a route or action it lacks gets a clear 403 and no data in the response.
- PII reveal and export from the UI require a reason and an access-log row.
- SLA breach is visible on the queue and the supervisor dashboard.
- If Redis pub/sub is down, the client polls and resyncs.

## Cut order

If Finance and Agent workspaces are cut under P2, the gate applies to the roles that remain, and the README says which journeys were cut. Do not claim eight roles in the demo if two workspaces were removed.
