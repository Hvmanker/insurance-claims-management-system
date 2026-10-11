# Scope, principles, and non-goals

Date: 2026-10-11
Status: accepted as the v3 plan

## Principles

- Extend the existing repo. Keep four service boundaries.
- One product: auto damage claims with repair estimates.
- Java rules decide. The Python worker returns facts, confidence, and flags.
- Events are at-least-once and idempotent. PostgreSQL is the source of truth.
- Rejection is a human action.
- Synthetic data only.
- Each business slice ends in a working demo and ships its UI.
- Record gaps. Do not describe the free demo as a compliant AWS deployment.

## Decisions

| Area | Decision | Why |
| --- | --- | --- |
| Codebase | Extend this repo. Record owner permission in the README. | Saves 1 to 2 weeks and keeps a working baseline. |
| Domain | Auto damage claims with repair estimates. Home and medical are later products. | This repo already has Auto and Home. Medical invoices were a drift in an earlier plan. |
| Identity | Spring sessions, BCrypt, INSURED and AGENT today, growing to eight roles. Same-origin behind Caddy. CSRF on writes. Cognito/OIDC is a config seam and a documented gap. | Auth exists. A rewrite adds risk without product value. |
| Decision authority | Java rules. Worker output is non-binding. | LLM scores are neither deterministic nor calibrated. |
| Event streaming | Kafka, transactional outbox and inbox, retry, then a dead-letter topic. | Fixes dual-write. SQS is not the Kafka dead-letter queue. |
| Queue and pub/sub | SNS fan-out to an SQS queue with its own DLQ for notification delivery. | Outbound email and SMS is a queue-shaped workload. |
| Batch | One containerized Spring Batch job expires stale MORE_INFO_REQUESTED claims. | The state model needs timeouts and the batch pattern needs a real job. |
| Object storage | S3 API against SeaweedFS or Garage, chosen at the start of slice 2. | MinIO community edition was archived in April 2026. |
| AWS API emulator | Floci, MiniStack, or LocalStack with a free account token. Spiked at the start of slice 5. | LocalStack's free image needs an auth token. Alternatives are young. |
| Frontend | Current stable Next.js (App Router, server components) as a Node container. SSE for live status, polling as fallback. | Dynamic rendering is the strongest Next.js signal. It costs one more container. Tailwind and shadcn/ui are the default, and the UI requirements remain binding if the framework changes. |
| CI/CD | A real Jenkinsfile and GitHub Actions, both calling `make ci`. | Two pipelines would drift. |
| IaC | No Terraform for now. Version-controlled Compose files, a deploy script, and written manual VM steps. | Keeps scope small. IaC stays a documented gap. Terraform can be added later. |
| Hosting | OCI Always Free Arm VM if available; otherwise full local Compose plus a labelled frontend-only preview. Decided in slice 8. | Free tiers change. The local demo is the guaranteed one. |

## Also decided

- API entry uses Spring Cloud Gateway behind Caddy. Caddy stays for HTTPS.
- Centralized logging uses Fluent Bit shipping to Loki, viewed in Grafana, self-hosted or on Grafana Cloud's free tier.
- Redis is part of the stack for shared sessions, caching, rate limiting, idempotency fast paths, distributed locks, and live-update fan-out. See [12-redis-roles-and-failure.md](12-redis-roles-and-failure.md).

## Non-goals for v1

- Real payments or insurer integrations.
- Real customer data.
- OCR for scanned documents.
- Production compliance claims.
- Multi-region availability.
- Any claim that the free demo satisfies the company's AWS standard.
- Enterprise identity features: SSO, MFA, and multi-tenant organizations. Those are future work.

## ADR links

- [ADR-001](../architecture-decisions/ADR-001-extend-existing-repo.md)
- [ADR-002](../architecture-decisions/ADR-002-auto-damage-claims-domain.md)
- [ADR-016](../architecture-decisions/ADR-016-compose-deploy-script-not-terraform.md)
