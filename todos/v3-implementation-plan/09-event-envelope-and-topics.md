# Event envelope and topics

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-005](../architecture-decisions/ADR-005-kafka-transactional-outbox.md)

Events are at-least-once with idempotent effects. This plan does not claim exactly-once delivery across PostgreSQL and Kafka.

## Envelope

Every event carries:

- `eventId`
- `eventType`
- `schemaVersion`
- `aggregateId`
- `occurredAt`
- `correlationId`
- `causationId`
- a minimal, non-sensitive payload

The Kafka key is the claim id. That preserves per-claim ordering inside a topic.

## Source of truth

A `contracts/` folder of JSON Schema plus example fixtures. Java tests and Python tests both validate those fixtures. The Java-only `common` module cannot be the worker's contract source. Slice 0 creates the folder. Slice 1 and slice 3 test it. Contract drift is a named test.

Payloads omit document bytes, prompts, and free-form personal data. Document events carry references. Files stay in object storage.

## Topics

| Topic | Producer | Consumers | Purpose |
| --- | --- | --- | --- |
| `claim.submitted` | claim-service | analysis worker | Claim and document references plus policy snapshot |
| `claim.analysis.completed` | analysis worker | approval-service | Extracted facts, confidence, evidence, flags |
| `claim.decided` | approval-service | claim-service, settlement-service, notifications | Approved, rejected, or more-info decision |
| `settlement.simulated` | settlement-service | claim-service, notifications | Simulated settlement record |
| `<topic>.DLT` | consumer error handler | operator replay command | Failed events with sanitized error metadata |

## Current topics to retire

`common.KafkaTopics` today defines:

- `claim.submitted` (keep, with the new envelope and snapshot)
- `claim.approved`
- `claim.rejected`
- `claim.settled`
- `claim.manual-decision`

`claim.decided` replaces the split approved, rejected, and manual-decision topics. `settlement.simulated` replaces `claim.settled` so the name matches the simulated record. Do this with a versioned contract, not a silent payload change on the old topic names.

## Notifications

Notification-service publishes to SNS. An SQS queue with its own DLQ handles outbound delivery retries. That SQS DLQ is not the Kafka dead-letter topic. See [ADR-006](../architecture-decisions/ADR-006-sns-sqs-notifications.md).
