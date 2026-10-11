# Slice 1: Thin end-to-end

Length: about 1 week
Depends on: slice 0 gate
UI: insured home, claim creation and status; adjuster queue and decision form; shared app shell

## Work

- Add the policies table, seeded synthetic data, and the new states through additive migrations.
- Shared outbox and inbox library in `common`.
- Role claims in the session and a permission check on every endpoint, starting with Insured and Adjuster.
- Sessions stored in Redis (Spring Session) so services stay stateless.
- Submit endpoint with an `Idempotency-Key` header. `claim.submitted` goes out through the outbox.
- Stub analysis consumer returning fixed facts that match a `contracts/` fixture.
- Rule engine in approval-service with the decision table.
- Adjuster approve, reject, and request-more-info endpoints.
- Simulated settlement and read-model update in claim-service.
- Minimal dynamic UI on the shared app shell: sign in, create and submit a claim, status updates (SSE with polling fallback), adjuster queue and decision.
- Audit rows and optimistic version on decisions.

## Gate

A browser end-to-end test passes. A crash between the database commit and publish still delivers the event, with no duplicate settlement.

## Tests introduced here

- Duplicate submit with the same idempotency key creates one claim and one `claim.submitted`.
- Crash between commit and publish still publishes once in effect after restart.
- Repeated Kafka delivery does not duplicate the decision or the settlement (tightened again in slice 4).
- A high-value claim waits in `REVIEW_READY` and cannot settle without approval.
- Another user's claim is denied on read and mutate.
- Java validation of `contracts/` fixtures (Python joins in slice 3).

## Notes

Measure memory in this slice. Four JVMs, the gateway, Next.js, and Kafka may exceed a 12 GB host. Record RSS before slice 8.

CSRF must be enabled on writes. The current filter chain permits all requests and disables CSRF.
