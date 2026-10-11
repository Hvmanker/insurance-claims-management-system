# ADR-005: Kafka with a transactional outbox and inbox

## Status

Accepted as a plan decision. The product changes are not built yet.

## Date

2026-10-11

## Context

Services today call `KafkaTemplate.send` from the same methods that commit database work. A crash can publish an event that was not stored, or store a claim and never publish it. Approval-service dedupes by primary key and republishes if the row exists, which is a partial answer.

Business problem: the demo must show that a claim is not lost and not paid twice.

Technical constraints: at-least-once delivery. Idempotent consumers. No claim of exactly-once across PostgreSQL and Kafka. SQS is not the Kafka dead-letter queue.

Scalability: the Kafka key is the claim id, so one claim stays ordered on a single-node broker. Multi-broker MSK is the AWS target and a Partly gap (EX-008).

Security: payloads are minimal and non-sensitive. DLT metadata is sanitized.

Cost: one Kafka broker, about 1 GB heap. No MSK.

## Decision

Use Kafka. Producers write state and an outbox row in one transaction. A publisher polls and marks rows sent. Consumers insert the event id and apply side effects in one transaction. Bounded retries, then `<topic>.DLT`. An operator command replays. The shared library lives in `common` for the Java services. The Python worker follows the same rules with `confluent-kafka` and its own processed-events table.

## Alternatives Considered

### Direct Kafka sends

- Pros: already in the repo.
- Cons: dual-write.
- Rejected: the slice 1 crash test exists to prove the outbox.

### SQS as the Kafka DLQ

- Pros: one AWS-shaped queue for everything.
- Cons: mixes two delivery systems and hides the topic replay story.
- Rejected: SQS is only for notification delivery.

### Exactly-once transaction spanning Postgres and Kafka

- Pros: a stronger claim.
- Cons: the plan explicitly does not make that claim.
- Rejected: idempotent effects are the guarantee we can test.

## Consequences

- Positive: a crash between commit and publish delays an event and does not drop it.
- Risks: a buggy inbox can still double-apply. Tests cover repeated delivery.
- Maintenance: one library, tested once. Contract fixtures stay in `contracts/`, not only in Java.
- Scaling: single broker has no replication. Say so in the README.

## Implementation Plan

- Folder impact: `common`, all three data-owning services, worker in slice 3, replay in slice 4.
- Deployment: existing Kafka container.
- Testing: crash between commit and publish, poison message, consumer crash, contract drift.
- Rollback: feature-flagging the publisher is unnecessary if slice 1's gate fails; fix the library before slice 2.

## Teaching Section

### Architecture in simple terms

Write the letter and the diary entry in one motion. Mail the letter afterward. If the mail truck crashes, the diary still has the letter.

### Business value

Reviewers look for lost events and double payments. This is the design that answers both.

### Long-term impact

New events use the same envelope and the same library.

### Best practices

Commit offsets only after the effect and the dedupe row are durable. Do not log the payload.

### Future optimization

MSK when an approved AWS account exists. The envelope does not change.
