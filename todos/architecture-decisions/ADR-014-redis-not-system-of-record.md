# ADR-014: Redis is shared state, never the system of record

## Status

Accepted as a plan decision. The product changes are not built yet.

## Date

2026-10-11

## Context

Claim-service already caches claim reads in Redis. Sessions are still local. The updated plan needs shared sessions, a fast idempotency hint, rate limits, a cache of policies and rules, job locks, and UI fan-out.

Business problem: restart and scale the HTTP tier without losing the claim ledger, and without a second source of truth.

Technical constraints: PostgreSQL constraints guard idempotency, decision versions, and settlements. Every Redis use has a fallback. Standard commands only, so Redis 8 (AGPLv3 option since May 2025) or Valkey both work. Choose the engine when deployment is decided.

Scalability: dev may use one instance with prefixes. Production uses a separate evictable cache and a non-evictable state instance. AWS target is ElastiCache with Multi-AZ and encryption.

Security: private network, password, ACLs, no FLUSHALL for app users, TLS in production, no personal data in keys, session holds user id and roles only.

Cost: 128 to 256 MB maxmemory, about 0.25 GB on the VM.

## Decision

Extend Redis to the six roles in the plan. Do not store claims, decisions, or settlements only in Redis. Fall back as specified: sign-in fails closed; idempotency falls through to the database; rate limits fail open with an alert; cache reads hit PostgreSQL; jobs skip or take a database lock; the UI polls.

## Alternatives Considered

### Leave Redis optional until measured

- Pros: the withdrawn PDF draft did this, and it saves RAM if cache is unused.
- Cons: the updated plan needs shared sessions in slice 1. Optional Redis would bring back sticky sessions.
- Rejected.

### Redis as the ledger

- Pros: fast writes.
- Cons: flush or eviction would drop claims.
- Rejected: tests include a flushed Redis and still expect one claim.

## Consequences

- Positive: stateless services and a defined degraded mode.
- Risks: operators treat Redis as durable. Persistence on the state instance is for sessions, synced every second, and is still not the claim ledger.
- Maintenance: event-driven cache eviction plus a short TTL. Role changes evict immediately.
- Scaling: ElastiCache later, same command set.

## Implementation Plan

- Folder impact: Spring Session in slice 1, idempotency cache in slice 4, hardening and rate limits in slice 5, cache and locks in slice 6, pub/sub in slice 7.
- Deployment: existing Redis service, then maxmemory, ACL, and persistence.
- Testing: outage, restart, flush, and cache eviction. See the test matrix.
- Rollback: a broken cache bypasses to PostgreSQL. Do not bypass the database unique constraint.

## Teaching Section

### Architecture in simple terms

Redis is a whiteboard. PostgreSQL is the filing cabinet. If the whiteboard is wiped, the files are still there, and people sign in again.

### Business value

The demo can survive a cache failure without a double settlement, which is the failure reviewers will ask for.

### Long-term impact

More instances of the gateway share sessions and rate limits without a new database design.

### Best practices

Expiry on every key. Prefixes. Metrics in Grafana. Readiness reports degraded, not crashed.

### Future optimization

Split cache and state instances before any production deploy. Pick Valkey or Redis when the licence choice is explicit.
