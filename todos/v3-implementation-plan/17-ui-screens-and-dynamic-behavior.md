# UI screens and dynamic behavior

Date: 2026-10-11
Status: UI requirements are binding. Next.js, Tailwind, and shadcn/ui are the default, not a commitment.
ADR: [ADR-010](../architecture-decisions/ADR-010-nextjs-app-router-default.md)

The UI is a set of role-specific workspaces over one claims backend. Every screen shows live data, offers only what the role may do, and leaves a record of who did what and why. Framework details can change if these requirements still hold.

## Screens

| Role | Screens and key interactions |
| --- | --- |
| Insured | Home with actions needed. Claim wizard (policy, incident, documents, review) with draft autosave and per-file upload status. Claim detail with status tracker, timeline, documents, and information requests. Notifications. |
| Agent | Client book. Start a claim for a client. Client claim status and documents. Notifications. |
| Adjuster | Work queue (assigned and unassigned pool, saved views, SLA badges). Review console with document viewer, extracted fields and evidence, the rule that routed the claim, decision form with coded reasons, escalate, refer to SIU, request information. Internal notes. |
| Supervisor | Team dashboard (workload, SLA, ageing). Escalation queue. Reassign and bulk actions. Claim search. Exports. |
| SIU investigator | Referral queue. Case view with flags, evidence, notes, and findings. Hold requests. |
| Finance officer | Settlement queue. Settlement detail marked simulated. Holds. Reconciliation report. |
| Auditor | Global search. Claim audit view (actor, time, rule version, evidence). Access-log view. Read-only exports. |
| Admin | Users and roles. Policy data. Rule configuration with versioning and approval. System health (dead-letter counts, event lag). |

Slice 1 ships a shared app shell, insured home, claim creation and status, and the adjuster queue and decision form. Later slices add upload, the evidence panel, error and 409 states, and then the full workspaces in slice 7.

## What "dynamic" means

- Navigation, buttons, and screens come from the user's permissions. A forbidden route shows an explanatory 403 page.
- Filters, sorting, pagination, and the selected claim live in the URL, so a view can be shared and survives a refresh.
- Claim status, queue counts, and SLA badges update without a refresh. Polling is the fallback. An in-flight transition shows "updating" because the read model is eventually consistent.
- The claim type and policy decide which fields and document types are required, from configuration.
- A stale decision shows who changed the claim and what changed. Nothing is overwritten silently.
- Optimistic updates are only for safe actions such as notes and filters. Decisions wait for server confirmation.

## Enterprise requirements for every screen

- Show the adjuster's authority limit and which rule sent a claim to review. Reject, hold, override, and escalate require a coded reason.
- Every claim has a timeline of who did what, when, and why. Decision screens show the rule version and the evidence used.
- Personal fields are masked in lists. Revealing one requires a reason and an access-log row. Personal data does not appear in URLs or client logs.
- Due times show on-track, at-risk, and breached. Queues sort by urgency by default.
- An in-app inbox has read state and links to the claim, fed by notification-service.
- Search by claim number, policy number, claimant alias, status, date range, and flag, always limited to what the role may see.
- Supervisors can reassign in bulk. Supervisor, finance, and auditor exports are role-scoped, confirmed, and logged.
- Idle timeout with a warning. CSRF protection. Re-authentication for rule approval and hold release.
- Keyboard operation, labelled controls, sufficient contrast, and automated axe checks. Insured and agent screens work on a phone. Staff screens are desktop-first. Every data screen has loading, empty, and error states. One design system.
- Every settlement is marked simulated. The public demo shows a synthetic-data banner.

## Rendering default

Server-rendered Next.js, forwarding the session cookie to the backend. SSE for live status, polling as fallback. Slice 7 fans live updates out through Redis pub/sub.

## Slice 7 gate

Every role completes its main journey in a browser after a refresh. Forbidden routes and actions are denied with clear messages. An auditor can trace any decision to its actor, time, rule version, and evidence. The axe checks pass.
