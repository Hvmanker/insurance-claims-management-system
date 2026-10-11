# Redis: roles, rules, and failure behaviour

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-014](../architecture-decisions/ADR-014-redis-not-system-of-record.md)

Redis is the performance and shared-state layer. It is never a system of record. PostgreSQL stays authoritative. Every use has a defined fallback when Redis is down.

The repo already uses Redis as a Spring Cache for claim reads in claim-service. This plan extends it to the roles below.

## Uses

| Use | What goes in Redis | If Redis is unavailable | Slice |
| --- | --- | --- | --- |
| Shared HTTP sessions (Spring Session) | Session data, so services and gateway instances hold no local session state | Sign-in fails with a clear "service unavailable" message. After a Redis restart, users sign in again. Claim data is unaffected. | 1 |
| Idempotency fast path | Recent idempotency keys with a short expiry | Falls through to the database unique constraint, which is always the real guard | 4 |
| Rate limiting | Token buckets in the gateway for sign-in, upload, and submit | Fails open with a small local limit and raises an alert | 5 |
| Cache | Policy lookups, rule configuration by version, role and permission sets, claim summaries for queues | Bypass the cache and read PostgreSQL | 6 |
| Distributed locks | One-runner locks for the outbox publisher, SLA evaluation, and orphan cleanup | Jobs skip a cycle or fall back to a database lock. Locks never guard money or decisions. | 6 |
| Live-update fan-out | Pub/sub of claim and queue changes so every UI instance can push to its clients | Clients fall back to polling and resync from the API on reconnect | 7 |

## Design rules

- Duplicate-settlement prevention, idempotency, and decision versioning are database constraints and version columns.
- Two roles, not one bucket: an evictable cache (maxmemory plus an eviction policy) and non-evictable state (sessions, locks, counters, every key with an expiry). Development may share one instance with key prefixes. Production runs them as separate instances.
- Key prefix per service and purpose. An expiry on every key. No personal data in keys. Values stay minimal. A session holds a user id and roles, not claim content.
- Cache invalidation is event-driven on decision, rule-version, and role-change events, plus a short expiry. A role change evicts that user's cached permissions immediately.
- Private network only. A password and ACLs per service. TLS in production. Application users cannot run `FLUSHALL` or `CONFIG`. Session ids rotate on sign-in.
- Persistence (append-only file, synced every second) on the state instance so a restart keeps sessions. Metrics for memory, hit ratio, evictions, clients, and latency go to Grafana. Readiness reports degraded mode instead of crashing the service.
- Start with a 128 to 256 MB maxmemory and measure. Budget about 0.25 GB on the VM.
- Redis 8 added AGPLv3 as a third licence option in May 2025. Valkey is a wire-compatible BSD fork. The code uses standard commands only, so either engine works. Choose when deployment is decided.
- AWS target: ElastiCache with Multi-AZ and encryption in transit and at rest.

## Tests

Slice 4: Redis unavailable, Redis restarted with state lost, and a duplicate submit after Redis is flushed still creates one claim. Slice 6: a role or rule change applies immediately through eviction.

## Business value

Shared sessions let the gateway and services restart without sticky cookies. The fallbacks keep a cache outage from becoming a wrong payment or a second claim.
