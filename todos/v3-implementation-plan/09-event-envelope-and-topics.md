# Event envelope and topics

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-005](../architecture-decisions/ADR-005-kafka-transactional-outbox.md)

Events are at-least-once with idempotent effects. This plan does not claim exactly-once delivery across PostgreSQL and Kafka.

## Envelope

Every event is a JSON object with `additionalProperties: false` and these required fields (see `contracts/envelope.schema.json`). Adding any new envelope field requires a new `schemaVersion` and a coordinated update of every producer and consumer; the closed envelope is deliberate, not an oversight.

- `eventId` (UUID)
- `eventType` (string; matches the topic / schema)
- `schemaVersion` (integer; `1` for the v1 event schemas in this slice)
- `aggregateId` (UUID; the claim id)
- `occurredAt` (RFC 3339 date-time)
- `correlationId` (UUID)
- `causationId` (UUID or `null` when there is no parent event)
- `payload` (object; event-specific, `additionalProperties: false` in each v1 schema)

The envelope does not carry a `producer` field. Producers are documented in the topics table only.

The Kafka key is the claim id (`aggregateId`). That preserves per-claim ordering inside a topic.

### `claim.decided` decision values

On the wire, `payload.decision` is lower-case: `approved`, `rejected`, or `more-info`. **`MORE_INFO_REQUESTED` is a claim status** in the read model; **`more-info` is the decision value** on `claim.decided` that leads to that status. Do not put status enum names on the event.

Legacy Java today uses upper-case `ApprovalDecision` (`APPROVED`, `REJECTED`) on approval rows and split topics (`claim.approved`, `claim.rejected`, `claim.manual-decision`). When those paths are unified onto `claim.decided`, map `APPROVED` → `approved`, `REJECTED` → `rejected`, and adjuster request-more-info → `more-info`. The JSON contract stays lower-case; do not change the Java enum in slice 0.

### Analysis worker field names (auto repair)

`claim.analysis.completed` uses auto-damage repair-estimate terms in `contracts/` (ADR-002), not the withdrawn medical names in [14-analysis-worker-contract.md](14-analysis-worker-contract.md):

| Contract field | Earlier medical draft (doc 14) |
| --- | --- |
| `estimateNumber` | `invoice_number` |
| `repairShopName` | `provider_name` |
| `dateOfLoss` | `date_of_service` |
| `estimateTotal` | `billed_amount` (with currency) |
| `DUPLICATE_ESTIMATE_NUMBER` flag | `DUPLICATE_INVOICE_NUMBER` |

## Source of truth

A `contracts/` folder of JSON Schema plus example fixtures. Java tests and Python tests both validate those fixtures. The Java-only `common` module cannot be the worker's contract source. Slice 0 creates the folder. Slice 1 and slice 3 test it. Contract drift is a named test.

Payloads omit document bytes, prompts, and free-form personal data. Document events carry references. Files stay in object storage.

## Topics

| Topic | Producer | Consumers | Purpose |
| --- | --- | --- | --- |
| `claim.submitted` | claim-service | analysis worker | Claim and document references plus policy snapshot |
| `claim.analysis.completed` | analysis worker | approval-service | Extracted facts, confidence, evidence, flags |
| `claim.decided` | approval-service | claim-service, settlement-service, notifications | Approved, rejected, or more-info decision |
| `claim.info.provided` | claim-service | approval-service | Insured uploaded documents in response to a more-info request (fixtures in Phase 1A) |
| `claim.analysis.requested` | approval-service | analysis worker | Emitted on first submit (Phase 1A) and when approval-service moves the claim back to analysis after more info (fixtures in Phase 1A) |
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
