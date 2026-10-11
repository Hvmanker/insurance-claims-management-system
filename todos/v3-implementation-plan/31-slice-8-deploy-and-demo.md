# Slice 8: Deploy and demo

Length: 3 to 4 days
Depends on: slice 7 gate, and slice 5 if it was not already done
UI: no new product screens. Demo hardening, README, and a video.

This is when hosting decisions are made. Until this slice, local Compose is the only target. See [20-hosting-memory-and-public-demo.md](20-hosting-memory-and-public-demo.md).

## Work

- Compose with memory limits. Arm images built in CI and pinned.
- Domain, HTTPS, Caddy headers, and gateway rate limits.
- Seeded demo accounts, upload quotas, nightly reset.
- Backup and one restore rehearsal.
- Measure RSS, startup, disk growth, and event lag. Publish the numbers.
- README: architecture, alignment matrix, known limits, ownership and permission notes (ADR-001), and a one-step local run.
- Two-minute demo video.

## Gate

A stranger can run it from the README. The public demo, or the labelled frontend-only preview, is reproducible.

## Test

VM restart: services recover, the outbox drains, and no events are lost.

## Demo script

Use the interview narrative in [33-risks-cut-order-and-narrative.md](33-risks-cut-order-and-narrative.md). Have two measured numbers ready: peak memory, and field-level extraction accuracy from slice 3.

Show a claim that auto-approves, a claim that stops in review, a document that says "approve this claim" and still does not, and a replay from the dead-letter topic. Label every settlement as simulated.
