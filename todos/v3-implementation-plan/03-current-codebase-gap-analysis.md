# Current codebase gap analysis

Date: 2026-10-11
Status: verified against the repo on this date
Slice: 0 must re-run this audit and write down what it finds. This file is the starting list, not a substitute for that gate.

## What already exists

Four Spring Boot services plus `common`:

- `claim-service` submits and reads claims, owns portal accounts, and publishes Kafka events.
- `approval-service` auto-approves at or below the configured limit and records manual decisions.
- `settlement-service` creates a settlement from an approved event.
- `notification-service` consumes workflow events and logs notification work.
- PostgreSQL with one database and a schema per owning service, Flyway per service.
- Kafka topics in `common`: `claim.submitted`, `claim.approved`, `claim.rejected`, `claim.settled`, `claim.manual-decision`.
- Redis is configured on claim-service (`spring.cache.type: redis`) and used with `@Cacheable` / `@CacheEvict` on claim reads.
- Docker Compose runs Postgres, Redis, Kafka, and the four services.
- GitHub Actions compiles modules and validates Compose. There is no Jenkinsfile.

## Identity today

`UserRole` is `INSURED` and `AGENT` only.

`ClaimService.decide` allows only `AGENT` to approve or reject a `SUBMITTED` claim, then publishes `claim.manual-decision`. Agents can read every claim. Insured users can read claims they own.

That AGENT behavior is an operational decision role. The v3 Agent role is a broker: own book only, no decisions. The v3 Adjuster is the person who decides within an authority limit. Slice 0 must write this mapping down before any role work. A silent rename would let brokers approve claims.

Sessions are servlet sessions on claim-service. The cookie is HttpOnly and SameSite strict. CSRF is disabled in `SecurityConfig`, and `authorizeHttpRequests` permits every request. Method security is enabled, so enforcement depends on annotations at call sites. v3 requires CSRF on POST, PUT, and DELETE, and a permission check on every endpoint.

Passwords use BCrypt strength 12. Migration `V2__add_portal_accounts_and_claim_reason.sql` inserts `agent@insureflow.local`. The README prints the seeded agent password. Slice 0 moves that credential to an environment-driven seed profile.

## Statuses and money rules today

`ClaimStatus` is `SUBMITTED`, `APPROVED`, `REJECTED`, `SETTLED`.

`ApprovalService.evaluate` approves when `estimatedAmount` is less than or equal to `claims.auto-approval-limit` (default `10000.00`) and publishes `claim.approved`. Above the limit, the method returns without a decision and leaves the claim for the agent queue. The README says higher-value claims are rejected. The code does not auto-reject. Slice 0 should record the code, and the README fix should match it.

v3 adds a nine-state core plus `SIMULATED_SETTLED`, and later `ESCALATED` and `UNDER_INVESTIGATION`. Amounts above the limit go to `REVIEW_READY`. They are never an automatic rejection. Existing status values move through an additive Flyway migration.

## What is absent

- No `policies` table. Policy number is a string on the claim.
- No document table, presigned upload, or object store.
- No transactional outbox or inbox. Producers send to Kafka from the application after or beside the database write.
- No Python worker, no `contracts/` JSON Schema, no analysis topic.
- No Spring Cloud Gateway, Caddy, or Next.js app. The portal is static files served by claim-service.
- No Spring Session. Sessions are local to the claim-service JVM.
- No dead-letter topic, replay command, or Spring Batch expiry job.
- No Jenkinsfile, Terraform, Compose memory limits, or log allowlist tests.
- Redis is a cache. It does not hold sessions, locks, rate-limit buckets, or UI fan-out.

## Ports

`claim-service` listens on 8081. Compose publishes `8081:8081`. The README tells readers to open `http://localhost:8085/dashboard` and also shows `http://localhost:8081/dashboard` and a curl to `http://localhost:8081/claims`. Slice 0 makes the README match Compose.

Other host ports in Compose: approval 8082, settlement 8083, notification 8084.

## Logging to verify in slice 0

The plan requires an audit of log call sites, especially notification-service, before the allowlist is enforced. This document does not claim that audit is finished.

## Implication

Slice 1 can keep the service boundaries and the Kafka install. It cannot keep the four-status enum, the auto-approve-only rule, in-process sessions, or direct Kafka sends as the durability story. See [22-delivery-overview.md](22-delivery-overview.md).
