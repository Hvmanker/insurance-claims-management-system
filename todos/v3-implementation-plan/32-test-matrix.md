# Test matrix

Date: 2026-10-11
Status: accepted plan

The failure cases are what distinguish this project. Each one gets a named automated test. Unit tests cover pure rules. Integration tests use real Postgres, Kafka, and an object store (Testcontainers). Browser tests cover the journeys. Test counts are not evidence of correctness on their own.

| ID | Failure case | What the test asserts | Slice | Gate tag |
| --- | --- | --- | --- | --- |
| TM-A | Cross-user claim read after cache warm | Another principal cannot read a claim after a different role warmed the `claims` cache; `decide` evicts stale entries | 0 | phase-0 |
| TM-B | PII in notification logs | Known fake policy, reason, and amount strings never appear in captured notification-service logs | 0 | phase-0 |
| TM-C | Contract fixture validation | Every `contracts/fixtures` valid file passes and every invalid file fails in Java | 0, 1, 3 | phase-0 |
| TM-D | High-value claim not auto-rejected | Claim over the auto-approval threshold stays awaiting human decision (legacy AGENT in slice 0); not auto-rejected | 0 | phase-0 |
| — | Duplicate submit with the same idempotency key | One claim and one `claim.submitted` event | 1 | |
| — | Crash between DB commit and publish | Event still published once in effect after restart | 1 | |
| — | Repeated Kafka delivery | No duplicate decision or settlement | 1, 4 | |
| — | High-value claim | Waits in REVIEW_READY; cannot settle without approval | 1 | |
| — | Another user's claim or document | Read and mutate both denied | 1, 2 | |
| — | Concurrent adjuster actions | Second gets a 409 from the version check | 4 | |
| — | Oversized, wrong-type, or bad-signature upload | Refused; no READY row | 2 | |
| — | Object store unavailable | Clear error; claim state unchanged | 4 | |
| — | Malformed or unreadable document | Routed to review with an extraction-failed reason | 3 | |
| — | Embedded instruction in a document | Never produces an approval | 3 | |
| — | LLM key missing, timeout, or invalid output | Deterministic path or review; never an approval | 3 | |
| — | Consumer crash mid-processing | Offset not committed; reprocessed once in effect | 4 | |
| — | Poisoned event | Lands in the DLT with sanitized metadata; replay succeeds | 4 | |
| — | PII in logs (platform-wide) | Known fake PII on error paths never appears in captured logs | 5 | |
| — | Stale MORE_INFO_REQUESTED claim | Batch job moves it to EXPIRED | 5 | |
| — | Contract drift | Java and Python both validate the same `contracts/` fixtures | 1, 3 | |
| — | VM restart | Services recover; outbox drains; no lost events | 8 | |
| — | Adjuster decides above their authority limit | Refused; the claim routes to ESCALATED for a supervisor | 6 | |
| — | User acts on their own claim or own policy | Denied and written to the audit trail | 6 | |
| — | Rule change proposed and approved by the same person | Refused; the change takes effect only after a second approver | 6 | |
| — | Auditor or Admin attempts a claim decision | Refused; read-only and configuration-only access verified | 6 | |
| — | Role opens a route or action it lacks | Clear 403 message and no data in the response | 7 | |
| — | PII reveal and export | Each requires a reason and is written to the access log | 6, 7 | |
| — | SLA breach | Claim is flagged on the queue and the supervisor dashboard | 6, 7 | |
| — | Redis unavailable | Sign-in shows a clear error; cache is bypassed; rate limits fail open with an alert; scheduled jobs skip safely; no claim data is lost | 4 | |
| — | Redis restarted with state lost | Users sign in again; no claim, decision, or settlement data is affected | 4 | |
| — | Duplicate submit with Redis flushed | Still exactly one claim; the database constraint is the guard | 4 | |
| — | Role or rule change while cached | New permissions and rule version apply immediately through eviction | 6 | |

## Notes on the Redis row

The source plan puts "Redis unavailable" on slice 4, and the assertion includes rate limits failing open. Gateway rate limiting is built in slice 5. Slice 4 asserts the Redis behaviors that exist then. Slice 5 adds the fail-open rate-limit assertion. Do not mark the rate-limit clause passed in slice 4.

## What is not a test

A large number of unit tests that never crash the publisher, never replay a dead letter, and never try another user's document.
