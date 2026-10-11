# Slice 0 — verified baseline behavior and role audit

**Subtask:** Phase 0.1 (docs only).  
**Repo HEAD verified:** 2026-10-11.  
**Method:** Treat the Phase 0 planning audit as a hypothesis; every fact below was re-checked in the current tree with `file:line` citations.

---

## Direct answer: AGENT review/decide on claims it did not submit

**Yes — an AGENT user can review and decide claims it did not submit.** Only `INSURED` may call `submit` (`claim-service/src/main/java/com/sunil/insurance/claims/service/ClaimService.java:46-48`). `decide` requires `UserRole.AGENT` and loads the claim by id with no owner check (`ClaimService.java:126-131`). Visibility for reads uses `findVisibleClaim`, which returns any claim by id for `AGENT` (`ClaimService.java:165-168`). List and dashboard paths expose all claims to `AGENT` via `findTop50ByOrderBySubmittedAtDesc` / `findAll` (`ClaimService.java:82-84`, `ClaimService.java:98-100`).

Under the product owner’s rule, **today’s `UserRole.AGENT` corresponds to ADJUSTER** (not broker). Renaming/migrating that meaning is **deferred to Phase 1B** via an **additive** Flyway migration; Phase 0 does not change role enums or permission checks.

---

## Role mapping decision (approved for Phase 0)

| Decision | Status |
|----------|--------|
| Map legacy `AGENT` → ADJUSTER semantics in docs/plan | Accepted; **code migration in Phase 1B** only |
| Keep V2-seeded `agent@insureflow.local` with role `AGENT` until 1B | **Approved** — demo decide flow stays on `== AGENT` checks |
| Introduce `BROKER` (UI label “Agent”) as a **new** enum value | Phase 0.6+; not part of this note |

---

## Security finding: claim read cache bypasses ownership

`ClaimService.get` is `@Cacheable` with key `#p0` (claim id only), so the cache entry is shared across users (`ClaimService.java:75-77`). After any user successfully loads a claim, a subsequent caller with the same id can receive the cached `ClaimResponse` **without re-running** `findVisibleClaim` (which enforces owner scope for non-AGENT users at `ClaimService.java:170-171`). Example: AGENT warms the cache; another INSURED who does not own the claim may then read it.

**Remediation:** Phase 0 subtask **0.7** (test **TM-A** — “Another user’s claim or document”, read half).

---

## Verified facts (audit checklist)

### Spring Security (`SecurityConfig` + controllers)

| Claim | Verified | Citation |
|-------|----------|----------|
| CSRF disabled | Yes | `claim-service/src/main/java/com/sunil/insurance/claims/config/SecurityConfig.java:27` |
| `anyRequest().permitAll()` | Yes | `SecurityConfig.java:28` |
| BCrypt strength 12 | Yes | `SecurityConfig.java:37` |
| Claim API requires authentication | Yes | `claim-service/src/main/java/com/sunil/insurance/claims/web/ClaimController.java:22` (`@PreAuthorize("isAuthenticated()")` on the controller) |
| `/api/auth/me` requires authentication | Yes | `claim-service/src/main/java/com/sunil/insurance/claims/web/AuthController.java:47-48` |
| Register/login are not `@PreAuthorize` | Yes (plan shorthand) | `AuthController.java:35-44` — public at filter level because of `permitAll()` |

**Correction vs plan wording:** The audit said “the controller” only has `isAuthenticated()`; in code, **`ClaimController` is class-scoped** and **`AuthController` only protects `/api/auth/me`**. Register and login are open at the HTTP-security layer.

### Domain: `ClaimStatus`

Four values: `SUBMITTED`, `APPROVED`, `REJECTED`, `SETTLED` — `common/src/main/java/com/sunil/insurance/common/ClaimStatus.java:4-7`.

### Kafka topic names (`KafkaTopics`)

| Constant | Topic string | Citation |
|----------|--------------|----------|
| `CLAIM_SUBMITTED` | `claim.submitted` | `common/src/main/java/com/sunil/insurance/common/KafkaTopics.java:4` |
| `CLAIM_APPROVED` | `claim.approved` | `KafkaTopics.java:5` |
| `CLAIM_REJECTED` | `claim.rejected` | `KafkaTopics.java:6` |
| `CLAIM_SETTLED` | `claim.settled` | `KafkaTopics.java:7` |
| `CLAIM_MANUAL_DECISION` | `claim.manual-decision` | `KafkaTopics.java:8` |

### Event publishing (no outbox)

Producers use `KafkaTemplate` directly; there is **no** outbox table or outbox publisher in the repo (grep for `outbox` in `**/*.java` is empty).

| Service | Mechanism | Citation |
|---------|-----------|----------|
| claim-service | `ClaimEventPublisher` → `kafkaTemplate.send(...).get()` | `claim-service/src/main/java/com/sunil/insurance/claims/config/ClaimEventPublisher.java:27-29` |
| approval-service | `ApprovalService` → `kafkaTemplate.send` | `approval-service/src/main/java/com/sunil/insurance/approval/service/ApprovalService.java:73-78`, `ApprovalService.java:83-88` |
| settlement-service | `SettlementService` → `kafkaTemplate` | `settlement-service/src/main/java/com/sunil/insurance/settlement/service/SettlementService.java:19`, publish path in same file |

### Flyway migrations

| Service | Versions | Citation |
|---------|----------|----------|
| claim-service | V1–V4 (`V4` = `claim_events`) | `claim-service/src/main/resources/db/migration/V1__create_claims.sql` … `V4__create_claim_events.sql` |
| approval-service | V1 | `approval-service/src/main/resources/db/migration/V1__create_claim_approvals.sql` |
| settlement-service | V1 | `settlement-service/src/main/resources/db/migration/V1__create_settlements.sql` |

### Seeded portal user (V2)

V2 inserts `agent@insureflow.local` with role `AGENT` and a BCrypt `password_hash` (`claim-service/src/main/resources/db/migration/V2__add_portal_accounts_and_claim_reason.sql:16-17`).

`README.md` documents the plaintext demo password on **line 66** (not reproduced here). **Approved:** keep this seed until Phase 1B; removal from an already-applied V2 is a separate Flyway/checksum concern (Phase 0.4).

### High-value claims vs README

Code: over-limit submitted claims are **not** auto-rejected; approval service returns without publishing (`approval-service/src/main/java/com/sunil/insurance/approval/service/ApprovalService.java:59-61`). Claim row stays `SUBMITTED` until manual agent decision.

README incorrectly states higher-value claims “are rejected” (`README.md:90`) and step 4 refers to a “rejection path” for >$10k (`README.md:97`) — **doc bug**; fix in subtask 0.9 / TM-D.

### Notification log leaks (PII / financials in logs)

`WorkflowNotificationListener` logs policy numbers, rejection reasons, and amounts:

- Approved: `policyNumber`, `approvedAmount` — `notification-service/src/main/java/com/sunil/insurance/notification/messaging/WorkflowNotificationListener.java:21`
- Rejected: `policyNumber`, `reason` — `WorkflowNotificationListener.java:26`
- Settled: `paidAmount` — `WorkflowNotificationListener.java:31`

Fix targeted in Phase 0.5 (TM-B).

### Docker Compose (current baseline)

| Claim | Verified | Citation |
|-------|----------|----------|
| Host publishes PostgreSQL 5432 | Yes | `docker-compose.yml:8-9` |
| Host publishes Redis 6379 | Yes | `docker-compose.yml:20-21` |
| Host publishes Kafka 9092 | Yes | `docker-compose.yml:30-31` |
| Hardcoded DB user/password `claims` | Yes | `docker-compose.yml:5-7`, `docker-compose.yml:60-62` (and sibling services) |
| Redis: no `requirepass` | Yes | `docker-compose.yml:18-25` (image defaults only) |
| App host ports 8081–8084 | Yes | `docker-compose.yml:65-66`, `88`, `108`, `125` |

**Approved for Phase 0 hardening (0.3):** drop data-store host ports; credentials from `.env`; Redis auth; **also remove host ports 8082–8084** so only claim-service **8081** is exposed on the host.

### README inconsistencies

| Issue | Citation |
|-------|----------|
| Dashboard URL uses **8085** | `README.md:57` |
| Compose maps claim-service **8081:8081** | `docker-compose.yml:65-66` |
| Service ports section lists 8085 on host | `README.md:113` |
| CI badge points at `sunilguntupalli/...` | `README.md:3` |
| Plaintext agent password in repo docs | `README.md:65-66` (see V2 hash above) |

### `.gitignore`

`.env` is ignored — `.gitignore:8`.

### CI workflow

| Claim | Verified | Citation |
|-------|----------|----------|
| `mvn --batch-mode verify` on PR/push | Yes | `.github/workflows/ci.yml:21` |
| `docker compose build` job | Yes | `.github/workflows/ci.yml:32-33` |
| Only Java test class in repo | Yes | sole `*Test.java`: `approval-service/src/test/java/com/sunil/insurance/approval/service/ApprovalServiceTest.java` |

**Approved for Phase 0.10:** `make ci` wrapping verify, compose config checks, and **gitleaks** with a **text-search fallback** for bcrypt/`$2[aby]$` patterns if gitleaks is unavailable.

---

## Java `log.(info|warn|error|debug)` call sites (full `rg` output)

Command run at repo root:

```text
rg -n "log\.(info|warn|error|debug)" --type java
```

Output:

```text
notification-service/src/main/java/com/sunil/insurance/notification/messaging/WorkflowNotificationListener.java:21:        log.info("Notification queued: claim {} for policy {} approved for {}", event.claimId(), event.policyNumber(), event.approvedAmount());
notification-service/src/main/java/com/sunil/insurance/notification/messaging/WorkflowNotificationListener.java:26:        log.info("Notification queued: claim {} for policy {} rejected: {}", event.claimId(), event.policyNumber(), event.reason());
notification-service/src/main/java/com/sunil/insurance/notification/messaging/WorkflowNotificationListener.java:31:        log.info("Notification queued: claim {} settled with payment {}", event.claimId(), event.paidAmount());
```

No other Java log calls at HEAD.

---

## Phase 0 approved product/engineering decisions (record)

| Topic | Decision |
|-------|----------|
| Legacy `AGENT` seed user | Keep until Phase **1B** |
| Cache ownership leak | Fix in Phase **0.7**; gate test **TM-A** |
| Topics `claim.info.provided`, `claim.analysis.requested` | **Table rows only** in Phase 0.8; **fixtures deferred to Phase 1A** |
| Compose host ports | Remove 5432/6379/9092; remove **8082–8084**; **only 8081** (gateway/claim-service) on host |
| Slice 0 tests | **TM-A** cache/ownership read, **TM-B** notification log PII, **TM-C** contract/schema fixtures, **TM-D** high-value stays non-rejected (`SUBMITTED`) |
| Secrets in CI | **gitleaks** preferred; grep/`$2[aby]$` fallback |

---

## Deviations from the planning audit (hypothesis vs code)

1. **Security `@PreAuthorize` scope** — Audit implied a single controller; **`AuthController` exposes unauthenticated register/login** while `ClaimController` is entirely authenticated-only (see table above).
2. **High-value behavior** — Audit matched code; README text is wrong (claims are not auto-rejected).
3. All other audit bullets in the Phase 0 plan **matched** the tree at verification time.

---

## Gate results (subtask 0.11)

*Not run in 0.1 — to be filled after Phase 0 implementation and clean-clone smoke.*
