# v3 Phased Execution Plan: Insurance Claims Management System

Prepared 2026-10-11 (IST) from `himank-verma/insurance-claims-management-system` at HEAD.
Sources read: every file in `todos/v3-implementation-plan/` (00 to 34, plus `ea-exceptions/EX-001..010`), `todos/architecture-decisions/ADR-001..017`, `README.md`, the repo tree, and these code files: `SecurityConfig.java`, `ApprovalService.java`, `SettlementService.java`, `WorkflowNotificationListener.java`, `V4__create_claim_events.sql`, `docker-compose.yml`, `.github/workflows/ci.yml`.

**Model rule: every agent in every phase (Planner, Architect, Designer, Coder, Tester, Simplifier, Verifier, E2E) runs on composer2.5.** Put this line in every hand-off and every cloud-agent launch.

---

## 1. How to read this plan

- The plan's own order stays the same: slice 0 → 1 → 2 → 3 → 4 → (5, which can move) → 6 → 7 → 8 (doc 22, 00-index).
- No phase starts until the previous phase's exit gate passes. Slice 5 is the only exception (00-index, 22).
- A phase is a PR-sized unit (or a short PR stack) that leaves `docker compose up` working and passes its own gate.
- Gate tests use the names in `32-test-matrix.md`. The rule from 32 applies throughout: "Test counts are not evidence of correctness on their own."
- Priority tags come from `33-risks-cut-order`: **P0** is never cut, **P1** is kept if at all possible, **P2** is cut first.

### Standard role loop for each phase
1. **Planner**: breaks the phase into tickets, checks prerequisites and open questions, and freezes scope.
2. **Architect**: writes or confirms the ADR delta, contracts, migrations, and ownership (doc 06). Rejects cross-schema joins (ADR-011).
3. **Designer** (UI phases only): defines screen states (loading, empty, error, 403, "updating") and keeps the role matrix in view (16, 17).
4. **Coder**: implements.
5. **Tester**: writes the named gate tests first where it can, using Testcontainers for Postgres, Kafka, and the object store (32).
6. **Simplifier**: removes duplication. There is one outbox/inbox library (doc 10), and no new abstractions beyond what the phase needs.
7. **Verifier**: checks the gate against the docs. That means no PII in logs (21), no Redis acting as a guard (12), and every transition has an audit row (04).
8. **E2E**: runs the browser journeys (from phase 1D on) and a clean-clone `docker compose up`.

---

## 2. Phase list (summary)

| # | Phase | Slice | Goal (one line) | Exit gate | Pri |
|---|---|---|---|---|---|
| 0 | Baseline & decisions | 0 | Verified behavior on record, README fixed, contracts and personas, Compose exposes only claim-service `8081`, `make ci` with gitleaks | Clean clone runs at the single README URL; verified-behavior note cites files; `contracts/` fixtures for the envelope and 4 events; 8 personas; TM-A–TM-D gate tests | P0 |
| 1A | Data & event foundation | 1 | New states and policies migrations; shared outbox/inbox lib in `common`; contract validation | Crash between DB commit and publish → published once in effect; Java validates `contracts/` fixtures | P0 |
| 1B | Identity hardening | 1 | Redis Spring Session, CSRF on writes, permission check on every endpoint, AGENT→Adjuster mapping, idempotent submit | Duplicate submit with same key → one claim and one `claim.submitted`; another user's claim denied on read and mutate | P0 |
| 1C | Decision pipeline | 1 | Stub analysis consumer → rule engine (decision table) → adjuster decide → simulated settlement → read model | High-value claim waits in REVIEW_READY and cannot settle without approval; repeated Kafka delivery → no duplicate decision or settlement | P0 |
| 1D | App shell & first journey | 1 | Next.js shell (+ gateway), insured create/status, adjuster queue/decision; memory measured | Browser E2E journey passes (slice 1 gate); RSS recorded | P0 |
| 2 | Documents | 2 | Object-store pick, presigned upload/confirm, ownership, orphan cleanup, fixture generator v1, upload UI | Another user's document denied; bad uploads refused with no READY row; submit without READY doc refused; orphans cleaned | P1 |
| 3 | Analysis worker | 3 | Python worker, deterministic extractor, LLM adapter off by default, eval set and scores, evidence panel | Malformed doc → review; injected instruction never approves; missing key/timeout/invalid output never approves; Java and Python validate same fixtures | P0 (eval + rules-only) / P1 (worker) |
| 4 | Reliability hardening | 4 | Retry→DLT→replay, 409s, correlation ids, Redis fast path & outage behavior, error UI | Poisoned event → DLT and replay succeeds; consumer crash reprocessed once; concurrent adjuster → 409; store down → state unchanged; Redis tests (no rate-limit clause) | P1 |
| 5A | CI & observability | 5 | Extend Phase 0 `make ci` with Jenkinsfile, JSON logs, full PII allowlist/mask, Fluent Bit→Loki→Grafana, Trivy | Every Jenkins stage runs a real command and can fail; logs reach Grafana with correlation ids; platform-wide log-leak gate passes | P1 |
| 5B | Platform services | 5 | Emulator spike, SNS→SQS+DLQ, Redis hardening + gateway rate limits, Spring Batch expiry, deploy script, EA records | Stale MORE_INFO_REQUESTED → EXPIRED; rate limits fail open with alert; `docs/ea-exceptions/` written | P1 (cron is P2) |
| 6A | Roles, SoD & escalation | 6 | All 8 roles in the API, authority limits, own-claim/own-policy denial, ESCALATED, append-only audit | Over-limit → refused and ESCALATED; own claim/policy denied and audited; Auditor/Admin decision refused | P0 |
| 6B | Operations workflow | 6 | UNDER_INVESTIGATION, assignment/SLA, notes vs messages, settlement holds, notifications table, agent book | SLA breach flagged for queue and supervisor query; hold blocks SIMULATED_SETTLED; SIU cannot decide | P1 |
| 6C | Governance & caching | 6 | Versioned rules with second approver, access log, reports, Redis caches with eviction, job locks | Same-person rule change refused; every decision stores actor and rule version; PII reveal/export need reason and log row; cache eviction applies immediately | P0/P1 |
| 7A | Shell & core workspaces | 7 | Design system, role nav, 403/idle; Insured + Adjuster full workspaces; live updates through Redis pub/sub | Those roles' journeys survive refresh; 403 with no data; pub/sub down → polling resyncs | P0/P1 |
| 7B | Staff workspaces | 7 | Supervisor, SIU, Auditor, Admin workspaces; PII reveal-and-log UI; exports | Auditor traces any decision to actor, time, rule version, evidence; axe passes | P1 |
| 7C | P2 workspaces | 7 | Finance and Agent workspaces, or an explicit documented cut | Journeys pass, or the README states the cut and no "eight roles" claim is made | P2 |
| 8 | Deploy & demo | 8 | Memory limits, pinned Arm images, Caddy/HTTPS, demo controls, backup/restore, numbers, README, video | A stranger runs it from the README; VM restart → outbox drains and no events are lost | P1 (VM is P2) |

Estimated total: about the plan's 8 to 10 weeks part-time (01). Splitting phases adds no scope. It adds gates.

---

## 3. Phase detail

### Phase 0: Baseline & decisions (slice 0, 1 to 2 days)
- **Deliverables**
  - README: record repository ownership and permission status (ADR-001). The CI badge points at `Hvmanker/insurance-claims-management-system`.
  - README: fix the port story. Compose publishes `8081:8081`, but the README says to open `localhost:8085/dashboard` and also lists `8081`. The README also claims that high-value claims are "rejected". `ApprovalService.evaluate` actually returns without deciding.
  - A verified-behavior note that cites files:
    - The AGENT role can decide any SUBMITTED claim and read all claims.
    - Redis is used as Spring Cache on claim reads.
    - `SecurityConfig` has `csrf.disable()` and `anyRequest().permitAll()`, with BCrypt 12.
    - The four-value status enum.
    - Producers use direct `KafkaTemplate.send` (no outbox). Approval and settlement republish when a row already exists.
    - Migrations V1–V4.
    - Log call sites.
  - A log audit. `WorkflowNotificationListener` today logs `policyNumber`, the rejection `reason`, and amounts. That breaks the doc-21 allowlist and must be listed.
  - **Notification log fix (slice 0):** log only `claimId` and event type in notification-service (TM-B). The **full** doc-21 allowlist, Logback mask, and Python filter stay in **slice 5A**.
  - Confirm and extend ADRs: nine core states per **ADR-009** (`SIMULATED_SETTLED` inside the nine; no claimant-withdrawal state; `ESCALATED` and `UNDER_INVESTIGATION` in slice 6); **ADR-015** two-step `BROKER` vs legacy `AGENT`; **ADR-018** authority limits (enforcement in 6A, doc only in slice 0).
  - `contracts/` with the envelope and schemas and fixtures for the four business events (`claim.submitted`, `claim.analysis.completed`, `claim.decided`, `settlement.simulated`).
  - Personas and seeds for all 8 roles, including agent-to-client links.
  - Move the seeded agent credential to an env-driven seed profile; **0.4** removes the V2 bcrypt hash and re-seeds from env (21).
  - **Compose hardening (slice 0):** drop host ports on Postgres, Redis, Kafka, and the non-gateway services; only **claim-service `8081:8081`** is published (not deferred to slice 5B or 8).
  - **`make ci` skeleton (slice 0):** `mvn verify`, `docker compose config`, compose port check, and **gitleaks** (with `rg` fallback). Slice **5A** adds Jenkins, Trivy, JSON logs, and Grafana — it does not re-introduce gitleaks.
- **Prereqs:** none.
- **Gate:** the doc-23 exit checks.
- **Roles:** Planner, Architect (lead), Coder (README/seed), Verifier, E2E (clean-clone run).
- **Risks:** hidden defects (33). Do not rename AGENT silently (03).

### Phase 1A: Data & event foundation (slice 1, part 1)
- **Deliverables**
  - Additive Flyway migrations: new states and old→new mapping (04, ADR-009); the `policies` table with synthetic seeds (07).
  - Outbox and inbox library in `common`, built and tested once (10). The publisher claims work with a **Postgres advisory lock** (slice 1 onward) so one claim's outbox events publish **in order**. **`FOR UPDATE SKIP LOCKED` with multiple publishers is rejected** because it can interleave events for the same claim. **Redis locks apply only to later scheduled jobs** (slice 5 batch expiry, slice 6 one-runner jobs), not the outbox publisher.
  - Envelope types; Java fixture validation against `contracts/`.
  - Versioned `claim.decided` and `settlement.simulated` topics alongside the old ones. Retire the old topics with a versioned contract, not a silent payload change (09).
- **Gate tests:** "Crash between DB commit and publish"; "Contract drift" (Java half).
- **Roles:** Architect (lead), Coder, Tester, Simplifier, Verifier.
- **Risks:** a buggy inbox double-applies (ADR-005).

### Phase 1B: Identity hardening (slice 1, part 2)
- **Deliverables**
  - Spring Session in Redis.
  - CSRF on POST/PUT/DELETE.
  - Explicit per-endpoint authorization for Insured and Adjuster.
  - Map today's AGENT to Adjuster, and migrate the seed.
  - `Idempotency-Key` on submit backed by a DB unique constraint.
  - Submit validates the policy and puts the policy snapshot (coverage window, limit) on `claim.submitted` (07).
  - Session-id rotation on sign-in (ADR-003).
- **Gate tests:** "Duplicate submit with the same idempotency key"; "Another user's claim … read and mutate denied" (claims half).
- **Roles:** Architect, Coder, Tester, Verifier.
- **Risks:** brokers keep approve rights through a rename (03). Redis down → sign-in fails closed (by design).

### Phase 1C: Decision pipeline (slice 1, part 3)
- **Deliverables**
  - A stub analysis consumer publishing a valid `claim.analysis.completed` fixture (14).
  - The 5-rule decision table in approval-service, thresholds in config, and an audit of the rules that passed (05).
  - Adjuster **approve** and **reject** (coded reason) endpoints with an optimistic `version` and audit rows. **Request-more-info is slice 2** (with documents).
  - Settlement → SIMULATED_SETTLED.
  - The claim-service read model fed through the inbox.
  - Decide whether to reuse the existing `claim_events` timeline table (V4) or add a separate audit table. The gap analysis (03) does not mention V4.
- **Gate tests:** "High-value claim waits in REVIEW_READY"; "Repeated Kafka delivery → no duplicate decision or settlement" (slice-1 level).
- **Roles:** Architect, Coder, Tester, Simplifier, Verifier.
- **Risks:** rule bugs become the new source of bad approvals (ADR-004).

### Phase 1D: App shell & first journey (slice 1, part 4)
- **Deliverables**
  - Next.js app shell: sign-in, create/submit (auto only, ADR-002, so drop the Home path), status (SSE with polling fallback), adjuster queue and decision form.
  - Spring Cloud Gateway in front (ADR-013 allows it in slice 1).
  - Compose memory limits. Record RSS (20, 24).
- **Gate:** the slice 1 browser E2E passes and the 1A crash test is still green.
- **Roles:** Designer, Coder, E2E (lead), Verifier.
- **Risks:** more than 12 GB on four JVMs + gateway + Next + Kafka. Measure now. Lower heaps before merging services (33).

### Phase 2: Documents (slice 2, 4 to 5 days)
- **Start-of-phase decision:** SeaweedFS vs Garage, decided by adapter integration tests (34; MinIO is excluded).
- **Deliverables**
  - Document table (13).
  - Presigned PUT, then confirm with checksum, signature, size, and type allowlist.
  - Signed downloads for the owner or adjuster.
  - Orphan cleanup.
  - Fixture generator v1 (15).
  - Insured upload with per-file status; adjuster document viewer.
  - **Request-more-info** endpoint (moved from slice 1): adjuster asks for documents; insured response flows **`claim.info.provided`** → approval-service transition → **`claim.analysis.requested`** (06, 04).
  - Simple **scheduled expiry** for stale `MORE_INFO_REQUESTED` → `EXPIRED` in approval-service (Spring Batch repackaging in slice 5).
  - Submit now requires a READY document. That means DRAFT→SUBMITTED becomes real, and the slice-1 E2E has to be updated.
- **Gate tests:** "Another user's … document"; "Oversized, wrong-type, or bad-signature upload"; "Submit without a READY document is refused" (25); **a claim in `MORE_INFO_REQUESTED` can always leave it** (info provided or expiry).
- **Roles:** Planner, Architect, Designer, Coder, Tester, E2E, Verifier.
- **Risks:** partial S3 compatibility. The adapter tests are the contract (ADR-007).

### Phase 3: Analysis worker (slice 3, 4 to 5 days)
- **Deliverables**
  - Python worker: `confluent-kafka`, manual commits, processed-events table.
  - `pypdf` plus template parsers.
  - Plain-code cross-checks.
  - LLM adapter off by default, temperature 0, Pydantic validation.
  - Eval set including injection text, an eval script, and published precision/recall.
  - Thresholds tuned in config.
  - Evidence panel UI (14, 15, 26).
- **Gate tests:** "Malformed or unreadable document"; "Embedded instruction in a document"; "LLM key missing, timeout, or invalid output"; "Contract drift" (Python half).
- **Roles:** Architect, Coder, Tester (lead on eval), Designer, E2E, Verifier.
- **Risks:** LLM free-tier terms (33). Prompts and document text must never be logged (15).

### Phase 4: Reliability (slice 4, 3 to 4 days)
- **Deliverables**
  - Bounded retry, then `<topic>.DLT` with sanitized metadata.
  - Operator replay command.
  - Correlation ids on every hop.
  - Redis idempotency fast path.
  - Error, retry, and 409 UI states.
- **Gate tests:**
  - "Concurrent adjuster actions"
  - "Object store unavailable"
  - "Consumer crash mid-processing"
  - "Poisoned event"
  - "Repeated Kafka delivery" (tightened)
  - "Redis unavailable": do **not** mark the rate-limit clause (32)
  - "Redis restarted with state lost"
  - "Duplicate submit with Redis flushed"
- **Roles:** Tester (lead), Coder, Verifier, E2E.
- **Risks:** treating Redis as a guard (12).

### Phase 5A / 5B: Standards layer (slice 5, 4 to 5 days; can move)
- **Recommendation:** run **5A right after Phase 1D or 2**. Slice 5 does not depend on roles or UI (22). Phase 0 already ships `make ci`, gitleaks, and the notification-service TM-B fix; 5A extends CI and logging platform-wide. Run 5B after Phase 4, where the plan puts it.
- **5A deliverables**
  - Extend the Phase 0 `make ci` target with a real Jenkinsfile (GitHub Actions already call `make ci`).
  - Trivy and dependency checks (gitleaks remains from Phase 0).
  - JSON logs, full allowlist, Logback mask, Python filter (beyond the slice-0 notification fix).
  - Fluent Bit → Loki → Grafana.
- **5A gate:** "PII in logs"; every Jenkins stage can fail; logs reach Grafana with correlation ids.
- **5B deliverables**
  - Emulator spike (Floci, MiniStack, or LocalStack) → SNS→SQS with its own DLQ.
  - Redis hardening: ACLs, maxmemory, separate cache and state roles, AOF, metrics.
  - Gateway rate limits.
  - Spring Batch expiry job with a cron container.
  - Deploy script and manual VM steps (ADR-016).
  - Copy EA records into `docs/ea-exceptions/`.
- **5B gate:** "Stale MORE_INFO_REQUESTED claim"; rate-limit fail-open with an alert.
- **Roles:** Architect, Coder, Tester, Verifier.
- **Risks:** emulator gaps (33). The emulator must not silently replace the slice-2 store (28).

### Phase 6A / 6B / 6C: Enterprise roles (slice 6, 6 to 8 days; API only)
- **6A:** 8-role permission checks on every endpoint, authority limits in config, segregation of duties (own claim, own policy), ESCALATED, denied attempts audited (append-only).
  - Tests: "Adjuster decides above their authority limit"; "User acts on their own claim or own policy"; "Auditor or Admin attempts a claim decision".
- **6B:**
  - UNDER_INVESTIGATION. SIU findings return the claim to REVIEW_READY and never auto-reject.
  - Assignment, due times, scheduled SLA evaluation, reassign.
  - Notes vs messages as separate records.
  - Settlement holds.
  - Notifications table with read state.
  - Agent book scoping.
  - Test: "SLA breach" (API/query half).
- **6C:**
  - Versioned rule config with a second approver; rule version on every decision.
  - Access log for reveal and export.
  - Report queries (SLA, workload, ageing, reconciliation).
  - Redis caches with event-driven eviction.
  - Redis one-runner locks with a DB fallback.
  - Tests: "Rule change proposed and approved by the same person"; "PII reveal and export" (API half); "Role or rule change while cached".
- **Prereq:** the Phase 4 gate. The Phase 5 gate as well if the team required it (29).
- **Why split:** this is the plan's largest API slice. 6A is the P0 core and can ship alone.
- **Roles:** Architect (lead on matrix), Coder, Tester, Simplifier, Verifier.
- **Risks:** scope creep. API rules for Finance and Agent must still exist even if their screens are cut (08).

### Phase 7A / 7B / 7C: Enterprise UI (slice 7, 8 to 10 days)
- **7A:**
  - Design system, role-aware nav, 403/error pages, idle-timeout warning, URL-state filters.
  - Full Insured workspace (wizard with draft autosave, tracker, information requests, notifications).
  - Full Adjuster workspace (queue with saved views and SLA badges, review console, internal notes).
  - Redis pub/sub live updates with polling fallback.
  - Tests: "Role opens a route or action it lacks"; "If Redis pub/sub is down, the client polls and resyncs" (30).
- **7B:**
  - Supervisor, SIU, Auditor, and Admin workspaces.
  - Masking with reveal-and-log; confirmed and logged exports.
  - Tests: "PII reveal and export" (UI half); "SLA breach" (UI half); auditor trace; axe.
- **7C:** Finance and Agent workspaces (P2). If cut, apply the cut rule in doc 30.
- **Gate (all of 7):** each remaining role's main journey passes in a browser after a refresh; axe passes.
- **Roles:** Designer (lead), Coder, E2E (Playwright per role), Tester, Verifier.
- **Risks:** RAM and accessibility debt. Never imply eight roles if 7C is cut.

### Phase 8: Deploy & demo (slice 8, 3 to 4 days)
- **Decisions here:** OCI VM vs local + labelled preview; domain or tunnel; Redis 8 vs Valkey (34).
- **Deliverables**
  - Memory limits, pinned Arm images built in CI.
  - Caddy headers and HTTPS.
  - Seeded demo accounts, quotas, nightly reset.
  - Backup and one restore rehearsal.
  - Published RSS, startup, disk, and event-lag numbers.
  - README with the alignment matrix and the author credit.
  - 2-minute video.
- **Gate test:** "VM restart" (or the Compose restart equivalent, ADR-008); a stranger runs it from the README.
- **Roles:** Planner, Coder, E2E (lead), Verifier.
- **Risks:** the free VM is unavailable or reclaimed. Fall back to local plus a labelled preview (33).

---

## 4. Gaps, contradictions, and ambiguities found

1. **The gateway and Next.js container in slice 1 are ambiguous.** Doc 24 doesn't list them. Doc 11 says the slice 1 shell plus the slice 5/8 edge. ADR-013 says slice 1 *can* add the gateway. The memory note in 24 assumes it. *Recommendation:* add both in 1D so memory is measured honestly.
2. **Outbox publisher lock — resolved.** Slice **1A** uses a **Postgres advisory lock** on the outbox publisher (per-claim order). Redis one-runner locks for **scheduled jobs** arrive in slices 5–6 (doc 12). `FOR UPDATE SKIP LOCKED` on outbox rows was considered and **rejected**.
3. **MORE_INFO exit path — resolved.** Request-more-info is **slice 2** with documents. Slice **2** ships a simple expiry job; slice **5** repackages it as Spring Batch. Gate: a claim in `MORE_INFO_REQUESTED` can always leave (info or expiry).
4. **The "submit needs a READY document" change (slice 2) alters the slice-1 contract and journey.** Plan the E2E update in Phase 2.
5. **Status ownership — resolved (06, ADR-009).** **approval-service** owns every transition from `ANALYSIS_PENDING` onward, including `MORE_INFO_REQUESTED` → `ANALYSIS_PENDING` and → `EXPIRED`. claim-service publishes **`claim.info.provided`** only; approval-service emits **`claim.analysis.requested`** after the transition. The read model in claim-service learns via inbox events.
6. **The worker contract fields look like the withdrawn medical draft.** `provider_name` and `date_of_service` (14) are not auto repair-estimate terms (ADR-002, date of loss). Confirm the field names in `contracts/` during Phase 0.
7. **Slice 0 says "write the ADRs" for the state machine, decision table, role model, and authority limits.** ADR-009, 004, 015, and **018** (authority limits) are the plan record after slice 0 decision docs.
8. **The gap analysis (03) omits migrations V3 and V4.** V4 adds a `claim_events` timeline table (actor, title, detail). It overlaps with the v3 audit table and has no reason code, version, or correlation id.
9. **Current logs already break doc 21.** The notification listener logs policy numbers, rejection reasons, and amounts.
10. **Compose exposes Postgres (5432), Redis (6379), and Kafka (9092) on the host with default `claims/claims` credentials.** That conflicts with "private network only" (11, 12). **Slice 0** closes those host ports and moves credentials to `.env` (only `8081` published). Slice 5B still hardens Redis ACLs; slice 8 covers production edge deploy.
11. **The Kafka heap doesn't match the budget.** Compose uses `-Xmx256m`; doc 20 and EX-008 budget about 1 GB. Measure in 1D.
12. **The test base is thin.** Only `ApprovalServiceTest` exists. Testcontainers and Playwright have to be introduced from scratch, which is why the CI skeleton is in Phase 0.
13. **The slice 5/6 dependency wording.** 00-index says only slice 5 may break the gate order. Doc 29 says slice 6 depends on slice 4 "unless the team requires slice 5". The Planner must state which applies.
14. **The object store is "picked in slice 0" in doc 20's memory table but in slice 2 in the slice plan.** The plan says follow slice 2 (34, ADR-007). Noted, not a blocker.
15. **Correlation ids:** the envelope carries `correlationId` from slice 0/1, but the "every hop" work is slice 4 and the Grafana gate is slice 5. Fine, but the Coder should propagate the id from 1A to avoid rework.

## 5. Open questions (doc 34) and which phase they block

| Question | Blocks | Owner |
|---|---|---|
| What today's AGENT maps to (recommend Adjuster) | Phase 0 → 1B | User + Architect |
| Final core enum (is SIMULATED_SETTLED inside the nine?) | **Settled in slice 0:** yes, nine states per ADR-009; `ESCALATED` / `UNDER_INVESTIGATION` in slice 6 | — |
| Committed V2 password hash | **Settled:** remove hash from V2 in **0.4**; demo-profile seeder from env; README password burnt (21) | — |
| SeaweedFS or Garage | Start of Phase 2 | Adapter tests |
| Floci, MiniStack, or LocalStack (token) | Start of 5B | Spike |
| Self-hosted Grafana or Grafana Cloud free tier | 5A, after RAM check | Measurement |
| Redis 8 or Valkey | Phase 8 | User |
| Domain or tunnel for HTTPS | Phase 8 | User |
| OCI VM or local + labelled preview | Phase 8 | Measurement |
| Whether Finance and Agent workspaces ship | 7C | User (P2 cut) |
| *(new)* Slice 5 timing: early 5A as recommended? Is the 5 gate required before 6? | Planning after 1D | User |
| *(new)* Worker contract field names for auto repair estimates | Phase 0 contracts | Architect |
| *(new)* Reuse `claim_events` vs a new audit table | Phase 1C | Architect |

## 6. Why the splits and merges
- **Slice 1 is split into 4.** It is the largest early slice (about 1 week) and mixes migrations, a library, security, the pipeline, and a new UI stack. Each part has its own test-matrix row, so each can gate alone. The crash test still lands in the first gate the plan wants (22).
- **Slice 5 is split into 2.** CI and logging are cheap, early-useful platform work. The emulator, Redis hardening, and batch depend on later facts (the slice-2 store, the slice-4 Redis behavior).
- **Slice 6 is split into 3.** P0 access control and SoD (6A) is separated from P1 operations (6B) and governance/caching (6C), so a time crunch cuts from the bottom of 33.
- **Slice 7 is split into 3.** It follows the cut order directly: P1 staff screens, then P2 Finance/Agent.
- **No merges.** Slices 0, 2, 3, 4, and 8 are already small and have distinct gates.
