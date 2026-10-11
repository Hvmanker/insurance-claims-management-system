# UI roles and permission matrix

Date: 2026-10-11
Status: accepted plan. The matrix is binding.
ADR: [ADR-015](../architecture-decisions/ADR-015-eight-role-model-and-segregation-of-duties.md)

Eight roles are in scope. The backend enforces every cell. The UI only reflects it. Slice 0 maps today's `AGENT` (who can decide any submitted claim and can read all claims) onto Agent or Adjuster. Do not keep both meanings under one name.

## Roles

| Role | Who they are | Main jobs | Hard limits |
| --- | --- | --- | --- |
| Insured | Policyholder | Report a claim, upload evidence, track status, answer information requests, see the decision and its reason | Own claims only |
| Agent | External broker | Start and track claims for clients in their book, upload documents for them | Own book only; no decisions |
| Adjuster | Claims handler | Work an assigned queue, review evidence and rule results, approve, reject with a coded reason, request information, refer to SIU, escalate | Decisions only up to their authority limit |
| Supervisor | Claims manager | Approve escalated claims, reassign work, watch SLAs and workload, resolve conflicts | Cannot decide on their own claims |
| SIU investigator | Fraud analyst | Work referred and flagged claims, record findings, request settlement holds | Cannot approve, reject, or settle |
| Finance officer | Settlement operations | See approved claims and simulated settlements, place or release holds, reconcile | Cannot change claim decisions |
| Auditor | Compliance | Read claims, decisions, rule versions, the audit trail, and the access log; export reports | Read-only |
| Admin | System administrator | Manage users, roles, policy data, and rule thresholds with a second approver; watch system health | Cannot decide claims |

## Permission matrix

| Capability | Insured | Agent | Adjuster | Supervisor | SIU | Finance | Auditor | Admin |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Create a claim | Own | Clients | — | — | — | — | — | — |
| Upload documents | Own | Clients | — | — | — | — | — | — |
| View claims | Own | Own book | Assigned | Team | Referred | Approved | All, read | Metadata only |
| View documents and evidence | Own | Clients | Yes | Yes | Yes | — | Yes, read | — |
| Approve or reject | — | — | Within limit | Any | — | — | — | — |
| Request more information | — | — | Yes | Yes | — | — | — | — |
| Refer to SIU or record findings | — | — | Refer | Refer | Findings | — | — | — |
| Reassign claims | — | — | — | Yes | — | — | — | — |
| Hold or release settlement | — | — | — | Yes | Request | Yes | — | — |
| View settlements | Status | Status | — | Yes | — | Yes | Yes, read | — |
| View audit trail | Own timeline | Clients' timeline | Claim timeline | Team | Referred | Settlement events | Everything | System events |
| Change rule thresholds | — | — | — | — | — | — | View history | Propose or approve (two people) |
| Manage users, roles, and policy data | — | — | — | — | — | — | — | Yes |
| Export reports | — | — | — | Team | — | Settlement | All | — |

## Delivery

Slice 1 enforces Insured and Adjuster. Slice 6 enforces all eight roles in the API, including segregation of duties. Slice 7 builds the workspaces. A forbidden route returns a clear 403 and no data.

P2 may cut the Finance and Agent screens first. The P0 requirement is role-based access control with an audit trail, which starts in slice 1.
