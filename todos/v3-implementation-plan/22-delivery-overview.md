# Delivery overview

Date: 2026-10-11
Status: accepted plan

The work is nine slices, numbered 0 through 8, about 8 to 10 weeks part-time. Each slice ends in a working demo. Every slice that adds business capability also ships its UI.

Slice 5 is platform work. It does not depend on roles or UI, so it can move earlier or later. No other slice starts before the previous gate passes.

| Slice | Doc | Gate in one line |
| --- | --- | --- |
| 0 | [23-slice-0-baseline.md](23-slice-0-baseline.md) | Clean clone runs, and verified behavior is written down. |
| 1 | [24-slice-1-thin-e2e.md](24-slice-1-thin-e2e.md) | Browser journey passes. A crash between commit and publish still delivers one event and does not duplicate settlement. |
| 2 | [25-slice-2-documents.md](25-slice-2-documents.md) | Many documents, owner checks, bad files refused, orphans cleaned. |
| 3 | [26-slice-3-analysis-worker.md](26-slice-3-analysis-worker.md) | Bad or hostile extraction goes to review and never to approval. |
| 4 | [27-slice-4-reliability.md](27-slice-4-reliability.md) | Poisoned events replay. Redis loss does not lose claim data. |
| 5 | [28-slice-5-standards-layer.md](28-slice-5-standards-layer.md) | Jenkins stages can fail. Logs reach Grafana. The log-leak test passes. |
| 6 | [29-slice-6-enterprise-roles.md](29-slice-6-enterprise-roles.md) | Each role is limited. Rule changes need two people. Decisions store actor and rule version. |
| 7 | [30-slice-7-enterprise-ui.md](30-slice-7-enterprise-ui.md) | Every role's journey survives a refresh. Axe passes. |
| 8 | [31-slice-8-deploy-and-demo.md](31-slice-8-deploy-and-demo.md) | A stranger can run it from the README. |

## UI shipped along the way

- Slice 1: app shell, insured home, create and status, adjuster queue and decision.
- Slice 2: upload progress and adjuster document viewer.
- Slice 3: evidence panel.
- Slice 4: error, retry, and 409 states.
- Slice 5: no product UI. Platform only.
- Slice 7: the remaining role workspaces and live updates.

## Why the first journey is slice 1

A stack that is wired layer by layer hides integration bugs until the end. The crash-between-commit-and-publish test is the point of the outbox, so it belongs in the first gate.

## Cut order

If time runs short, cut from the bottom of [33-risks-cut-order-and-narrative.md](33-risks-cut-order-and-narrative.md). P0 is never cut. Gates still apply to whatever remains.
