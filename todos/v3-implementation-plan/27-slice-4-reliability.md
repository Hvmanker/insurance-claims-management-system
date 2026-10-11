# Slice 4: Reliability hardening

Length: 3 to 4 days
Depends on: slice 3 gate
UI: error, retry, and conflict (409) states on the screens that already exist

## Work

- Retry with backoff, then a dead-letter topic, for every consumer.
- Operator replay command and a test that a failed event can be reprocessed.
- Tests for concurrent adjuster actions, duplicate events, an unavailable object store, a consumer crash, and Redis outage.
- Correlation ids across every hop.
- Redis idempotency fast path. The database constraint remains the real guard.

## Gate

A poisoned event lands in the DLT and replays successfully. Repeated delivery does not duplicate settlement. Stopping Redis degrades the app as designed, with no data loss.

## Tests

- Concurrent adjuster actions: the second gets 409 from the version check.
- Object store unavailable: clear error, claim state unchanged.
- Consumer crash mid-processing: offset not committed, reprocessed once in effect.
- Poisoned event: DLT with sanitized metadata, replay succeeds.
- Redis unavailable: sign-in shows a clear error, cache is bypassed, scheduled jobs skip safely, no claim data is lost.
- Redis restarted with state lost: users sign in again. Claims, decisions, and settlements are unchanged.
- Duplicate submit with Redis flushed: still exactly one claim.

Rate-limit fail-open is implemented with gateway rate limiting in slice 5. The slice 4 Redis tests cover the behaviors that exist by then: sessions, cache if any, and the idempotency fast path.
