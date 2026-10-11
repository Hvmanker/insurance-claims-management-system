# v3 implementation plan: documentation index

Date: 2026-10-11
Source: Insurance Claims Platform v3 Implementation Plan (Markdown, @Himank)
Status: teaching set for a plan that is not built yet

This folder explains the v3 plan. It does not change the Java services, Compose file, or CI. The product still asks for `contracts/` and `docs/ea-exceptions/` when those slices start. The copies here are the source to move then.

## What the plan commits to

The build order (slices 0 through 8) and the role-based UI requirements. Stack and hosting rows are working assumptions. They can change later without rewriting the slice plan.

Sections 7 and 8 of the source plan are deferred:

- Standards alignment is revisited when slice 5 starts. See [19-standards-alignment-matrix.md](19-standards-alignment-matrix.md).
- Free hosting is decided in slice 8. Until then, local Compose is the only target. See [20-hosting-memory-and-public-demo.md](20-hosting-memory-and-public-demo.md).

## Reading order

1. [01-executive-summary.md](01-executive-summary.md)
2. [02-scope-principles-and-non-goals.md](02-scope-principles-and-non-goals.md)
3. [03-current-codebase-gap-analysis.md](03-current-codebase-gap-analysis.md)
4. [04-domain-state-machine.md](04-domain-state-machine.md)
5. [05-decision-table-and-thresholds.md](05-decision-table-and-thresholds.md)
6. [06-service-ownership.md](06-service-ownership.md)
7. [07-synthetic-policy-store.md](07-synthetic-policy-store.md)
8. [08-enterprise-workflow-additions.md](08-enterprise-workflow-additions.md)
9. [09-event-envelope-and-topics.md](09-event-envelope-and-topics.md)
10. [10-outbox-inbox-and-replay.md](10-outbox-inbox-and-replay.md)
11. [11-component-architecture.md](11-component-architecture.md)
12. [12-redis-roles-and-failure.md](12-redis-roles-and-failure.md)
13. [13-document-upload-and-storage.md](13-document-upload-and-storage.md)
14. [14-analysis-worker-contract.md](14-analysis-worker-contract.md)
15. [15-extraction-evaluation-and-prompt-safety.md](15-extraction-evaluation-and-prompt-safety.md)
16. [16-ui-roles-and-permission-matrix.md](16-ui-roles-and-permission-matrix.md)
17. [17-ui-screens-and-dynamic-behavior.md](17-ui-screens-and-dynamic-behavior.md)
18. [18-backend-support-for-ui.md](18-backend-support-for-ui.md)
19. [19-standards-alignment-matrix.md](19-standards-alignment-matrix.md)
20. [20-hosting-memory-and-public-demo.md](20-hosting-memory-and-public-demo.md)
21. [21-security-pii-and-logging.md](21-security-pii-and-logging.md)
22. [22-delivery-overview.md](22-delivery-overview.md)
23. Slices: [23](23-slice-0-baseline.md) through [31](31-slice-8-deploy-and-demo.md)
24. [32-test-matrix.md](32-test-matrix.md)
25. [33-risks-cut-order-and-narrative.md](33-risks-cut-order-and-narrative.md)
26. [34-glossary-and-open-questions.md](34-glossary-and-open-questions.md)

## Related folders

- [ea-exceptions/](ea-exceptions/) — one record per Gap or Partly row. Copy into `docs/ea-exceptions/` in slice 5.
- [../architecture-decisions/](../architecture-decisions/) — ADR-001 through ADR-017.

## How to use this set

Read the gap analysis before changing code. Slice 0 must confirm today's behavior in a written list. Do not start a slice other than slice 5 until the previous gate passes. Slice 5 is platform work and may move earlier or later.
