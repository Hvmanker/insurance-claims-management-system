# ADR-015: Eight roles and segregation of duties

## Status

Accepted as a plan decision. Slice 1 ships Insured and Adjuster. Slice 6 completes the model. The product is not built yet.

## Date

2026-10-11

## Context

The repo has INSURED and AGENT. AGENT can read every claim and approve or reject any SUBMITTED claim. The v3 Agent is a broker with a book of clients and no decision rights. The v3 Adjuster decides within a limit. Six more roles exist for a real claims desk.

Business problem: show that the system, not the screen, stops the wrong person from deciding, exporting, or changing a rule.

Technical constraints: a permission check on every endpoint. Authority limits in config. Two-person rule changes. Append-only audit. Access log for reveals and exports.

Scalability: permission sets may be cached in slice 6 and must evict on role change.

Security: nobody decides a claim they submitted or a claim on their own policy. Auditors are read-only. Admins cannot decide claims. SIU cannot approve, reject, or settle. Finance cannot change claim decisions.

Cost: slice 6 and slice 7 are the largest slices. Finance and Agent screens are the first P2 cuts. The API rules for access control are P0.

## Decision

Adopt eight roles: Insured, Agent, Adjuster, Supervisor, SIU investigator, Finance officer, Auditor, and Admin. Enforce the permission matrix on the server. Slice 0 writes down how today's AGENT maps. Do not leave both meanings on one enum value.

## Alternatives Considered

### Keep AGENT as the decision role

- Pros: less migration.
- Cons: the UI plan's Agent cannot decide, and one name would mean two jobs.
- Rejected: slice 0 maps the name explicitly.

### UI-only hiding

- Pros: faster screens.
- Cons: a crafted request would still decide.
- Rejected: the matrix says the backend enforces every cell.

## Consequences

- Positive: the demo can show a 403, an escalation, and an auditor trace.
- Risks: scope. Cut Finance and Agent workspaces before cutting the audit trail.
- Maintenance: every new endpoint needs a matrix row.
- Scaling: role checks stay in the services even if a gateway is added.

## Implementation Plan

- Folder impact: security rules, seeds for eight personas, approval authority, slice 7 workspaces.
- Deployment: seed profiles, not passwords in the README.
- Testing: authority limit, own-claim, two-person rule change, auditor decision refused, 403 with no body data.
- Rollback: slice 1 can ship two roles. Do not skip the audit row to save time.

## Teaching Section

### Architecture in simple terms

Each person gets a badge. The door checks the badge. The badge is not a picture painted on the screen.

### Business value

Segregation of duties is how a claims operation avoids one employee approving their own loss. The demo should make that refusal visible.

### Long-term impact

SSO can change how the badge is issued. It should not change what the badge allows.

### Best practices

Coded reasons on reject, hold, and escalate. Re-authenticate for rule approval and hold release. Mask personal fields until a reason is logged.

### Future optimization

Finer permissions can replace broad roles later. Start with the eight named roles so the matrix stays readable.
