# Phase 0 runtime check

`scripts/phase0-runtime-check.sh` brings up a **throwaway** Docker Compose stack, runs the Verifier’s 15 Phase 0 gate checks against the live services, writes a timestamped log, and tears down only resources in project `phase0check`.

## Safety guard (required)

Every `docker compose` invocation uses `-p "$COMPOSE_PROJECT"`. The script **refuses to run** unless:

```bash
export COMPOSE_PROJECT=phase0check
```

If `COMPOSE_PROJECT` is unset or any other value, the script exits **2** before calling Docker. That prevents accidental `down -v` or builds against your normal compose project name.

## Prerequisites

- Docker and Docker Compose v2
- Bash, `curl`, and `python3` (JSON parsing)
- Optional: `nc` (secondary host-port probe), `shellcheck` (lint), Maven (only if a Kafka integration TM-B test exists)
- A **`.env`** file at the repo root (copy from `.env.example`). The script loads **only** `.env` for credentials and test configuration. It fails fast if `.env` is missing.
- When `SPRING_PROFILES_ACTIVE` includes `demo`, the script exits before Docker if any of `DEMO_AGENT_PASSWORD`, `DEMO_INSURED_PASSWORD`, or `DEMO_BROKER_PASSWORD` is unset (all three are listed in `.env.example` with placeholders).
- **Service JARs:** the script does not run Maven. Compose `up --build` copies pre-built jars via `Dockerfile.service`. Until the multi-stage `Dockerfile.service` change lands (so images build from a clean clone without local jars), run `mvn -DskipTests package` with **Java 21** once from the repo root before the script.

## Usage

From the repository root:

```bash
cp .env.example .env
# Edit .env — set strong passwords and SPRING_PROFILES_ACTIVE=demo for demo-user checks.

mvn -DskipTests package   # required until multi-stage Dockerfile.service merges
export COMPOSE_PROJECT=phase0check
./scripts/phase0-runtime-check.sh

# Self-test: list check numbers and names (no Docker)
./scripts/phase0-runtime-check.sh --list
```

Output is teed to `phase0-runtime-check-<UTC-timestamp>.log` in the repo root. Values that look like passwords or secrets from `.env` are masked in the log (`***REDACTED***`).

## What the script does

1. Validates `COMPOSE_PROJECT=phase0check` and loads `.env`.
2. `docker compose -p phase0check down -v`
3. `docker compose -p phase0check up -d --build`
4. Waits for Compose health (timeout `PHASE0_HEALTH_TIMEOUT_SEC`, default 900s).
5. Runs checks **01–15** in the order of the `CHECKS` array at the top of the script (reorder there to match the Verifier).
6. Prints `SUMMARY pass=… fail=… skipped=… total=15`
7. Exits **0** only if all 15 checks **PASS**; any **FAIL** or **SKIPPED** yields a non-zero exit.

Override the claim-service base URL with `PHASE0_BASE_URL` (default `http://localhost:8081`).

## Check list

The authoritative names and order live in the `CHECKS` array inside `phase0-runtime-check.sh`. They match the Verifier list:

1. Healthy services and only port 8081 published in `docker compose ps`
2. Host exposure via `docker compose port` (and `nc` as secondary; foreign listeners → SKIPPED)
3. Redis `NOAUTH` without password inside the container
4. Flyway on fresh Postgres; `portal_users` empty without `demo` profile
5. Demo sign-in after `down -v` and `up` with `demo` profile
6. Live TM-A cache authorization (curl + real Redis)
7. TM-B Kafka integration (SKIPPED if only unit tests exist)
8. Late `claim.decided` e2e (SKIPPED: `consumers not yet on the contract envelope (1A)`)
9. Duplicate `eventId` e2e (SKIPPED: `consumers not yet on the contract envelope (1A)`)
10. 5200.00 vs 5180.00 reconciliation (SKIPPED: `consumers not yet on the contract envelope (1A)`)
11. Decide/commit cache race (SKIPPED without a pause test hook)
12. README dashboard URL returns HTTP 200
13. TM-D high-value claim stays `SUBMITTED` until legacy AGENT decides
14. No stale `com.sunil`-typed Kafka messages after rename (always **SKIPPED** here: `upgrade path not exercised`)
15. Redis `claims*` cache flushed on deploy after rename (always **SKIPPED** here: `upgrade path not exercised`)

Checks **14** and **15** intentionally never **PASS** on the script’s default `down -v` then `up` flow, because empty topics and an empty Redis cache are guaranteed and would be a false green. A future upgrade gate must stand up a **pre-rename** (`com.sunil`) image stack with a warmed `claims*` cache entry and a published legacy `com.sunil`-typed Kafka event, run the documented topic reset and Redis flush, deploy **new** images, then verify the new stack starts, serves claim reads, and consumes new events without deserialization errors.

## Lint

CI pins [ShellCheck v0.10.0](https://github.com/koalaman/shellcheck/releases/tag/v0.10.0) (linux x86_64 static binary). Locally:

```bash
shellcheck -x scripts/phase0-runtime-check.sh
```

## Guard smoke test (no real stack)

Verify the project-name guard without touching Docker:

```bash
mkdir -p /tmp/phase0-guard/bin
printf '%s\n' '#!/bin/sh' 'echo docker invoked >&2; exit 99' > /tmp/phase0-guard/bin/docker
chmod +x /tmp/phase0-guard/bin/docker
export PATH="/tmp/phase0-guard/bin:$PATH"

unset COMPOSE_PROJECT
./scripts/phase0-runtime-check.sh; echo "unset exit=$?"

export COMPOSE_PROJECT=wrong-name
./scripts/phase0-runtime-check.sh; echo "wrong exit=$?"
```

Both should print exit code **2** and must not invoke the stub `docker` binary.
