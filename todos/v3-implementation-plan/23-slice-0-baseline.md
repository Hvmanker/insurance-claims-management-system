# Slice 0: Baseline and decisions

Length: 1 to 2 days
Depends on: nothing
UI: none

## Work

- Record the owner's permission in the README and in [ADR-001](../architecture-decisions/ADR-001-extend-existing-repo.md).
- Run the existing tests and Compose. Fix the 8081 versus 8085 port and URL inconsistencies in the README. Compose publishes claim-service as `8081:8081`.
- Audit the code and write a verified list:
  - what the AGENT role does today (it can decide any submitted claim and can read all claims),
  - what Redis is used for today (Spring Cache on claim reads),
  - log call sites, especially notification-service,
  - outbox or the lack of it,
  - error handling,
  - current status values (`SUBMITTED`, `APPROVED`, `REJECTED`, `SETTLED`),
  - auth details (CSRF disabled, all requests permitted at the filter chain, BCrypt 12),
  - migrations.
- Write the ADRs for the state machine, the decision table, the role and permission model, and authority limits. The set in `todos/architecture-decisions/` is the plan record. Slice 0 still has to confirm anything this audit contradicts.
- Create `contracts/` with the envelope and event schemas and fixtures.
- Define synthetic personas and seed data for all eight roles, including agent-to-client links (`claim-service/src/main/resources/fixtures/personas/`).
- Move the seeded credential out of the README into an environment-driven seed profile.

## Explicitly not in this slice

The object-store choice waits until slice 2. The AWS emulator spike waits until slice 5. Domain, HTTPS, and the VM wait until slice 8.

## Gate

The baseline runs from a clean clone, and a written list of verified (not assumed) repo behavior exists.

## Exit check

- `docker compose up` from a clean clone matches the README's single local URL.
- The verified-behavior note is committed with the slice, or stored with the slice notes, and it cites files.
- `contracts/` fixtures exist for the envelope and the four business events.
- Personas cover Insured, Agent, Adjuster, Supervisor, SIU, Finance, Auditor, and Admin.
