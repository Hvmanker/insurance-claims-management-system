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

Passwords use BCrypt strength 12.

### Existing Flyway Migrations Inventory

The `claim-service` schema currently executes four migrations (`claim-service/src/main/resources/db/migration/`):

1. **`V1__create_claims.sql`**: Creates `claims` table (`id`, `policy_number`, `claim_type`, `estimated_amount`, `status`, `created_at`, `updated_at`).
2. **`V2__add_portal_accounts_and_claim_reason.sql`**: Creates `portal_users` table (`id`, `email`, `password_hash`, `role`, `created_at`), adds `reason` and `insured_id` to `claims`. (The default demo user insertion was removed in Phase 0.4 and moved to the environment-driven seed profile).
3. **`V3__add_claim_description.sql`**: Adds `description VARCHAR(2000)` to `claims`, backfilling from `reason`.
4. **`V4__create_claim_events.sql`**: Creates `claim_events` timeline table (`id UUID`, `claim_id UUID REFERENCES claims`, `event_type VARCHAR(40)`, `title VARCHAR(120)`, `detail VARCHAR(1000)`, `actor VARCHAR(120)`, `occurred_at TIMESTAMPTZ`), indexed on `(claim_id, occurred_at ASC)`.

**Architectural Assessment of `claim_events` (V4)**:
Migration V4 introduces a timeline table used by the portal. However, it lacks immutable reason codes, optimistic lock `version`, rule set version, and `correlation_id` fields required by v3 audit compliance. Slice 1C and Slice 6 maintain `claim_events` as an end-user UI timeline while introducing an append-only `claim_audit_log` for regulatory compliance.

The README printed the seeded agent password; Phase 0.4 moved that credential to an environment-driven demo profile.

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

`claim-service` listens on 8081. In Phase 0.3, Docker Compose was hardened to publish only `8081:8081` on the host, isolating PostgreSQL (5432), Redis (6379), Kafka (9092), and internal service ports (8082, 8083, 8084) to the private Compose network.

## Logging audit verified in slice 0

The Phase 0.5 audit identified and eliminated sensitive data logging in `notification-service`:
`WorkflowNotificationListener.java` previously logged `policyNumber`, `approvedAmount`, `reason`, and `paidAmount`. This was remediated in Phase 0.5 (test matrix row `TM-B`), enforcing masked event logs in compliance with [21-security-pii-and-logging.md](21-security-pii-and-logging.md).

## Implication

Slice 1 can keep the service boundaries and the Kafka install. It cannot keep the four-status enum, the auto-approve-only rule, in-process sessions, or direct Kafka sends as the durability story. See [22-delivery-overview.md](22-delivery-overview.md).
