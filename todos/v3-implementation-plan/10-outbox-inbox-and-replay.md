# Outbox, inbox, and replay

Date: 2026-10-11
Status: accepted plan
Slice: library in slice 1; retry, DLT, and replay in slice 4
ADR: [ADR-005](../architecture-decisions/ADR-005-kafka-transactional-outbox.md)

## Pattern

Each producer writes business state and an outbox row in one database transaction. A publisher polls unsent rows, publishes, then marks them sent. A crash between commit and publish delays the event. It does not lose it.

Each consumer inserts the event id and applies side effects in the same transaction. A redelivered event does nothing. Offsets are committed only after the effect and the dedupe record are durable.

Failures take bounded retries with backoff, then the dead-letter topic. An operator command re-publishes a DLT event. A test proves a failed event can be reprocessed. DLT metadata is sanitized: no payloads, no document text.

## One library

Outbox and inbox are one shared library in `common`, used by claim, approval, and settlement. Build it once and test it once. The Python worker uses the same reliability rules with `confluent-kafka`, manual commits, and its own processed-events table. It does not import the Java library.

## What this does not promise

At-least-once plus idempotent consumers. Not exactly-once across Postgres and Kafka.

Redis is not part of this guarantee. From slice 4, recent idempotency keys may be cached for a fast answer. If Redis is flushed or down, the database unique constraint still allows one claim and one `claim.submitted` for a given key.

## Current repo

Producers use `KafkaTemplate.send` from service methods. Approval-service dedupes by primary key on `ClaimApproval` and, if a row exists, publishes again. There is no outbox table. A crash after commit and before send can drop an event. A crash after send and before commit can publish a decision that is not stored.

## Gate

Slice 1: a crash between the database commit and publish still delivers the event, and settlement is not duplicated.

Slice 4: a poisoned event lands in the DLT and replays successfully. Repeated delivery does not duplicate a decision or a settlement. A consumer crash mid-processing does not commit the offset. The event is reprocessed once in effect.

## Business value

Reviewers look for the dual-write bug. The outbox is the answer, and the crash test is the evidence.
