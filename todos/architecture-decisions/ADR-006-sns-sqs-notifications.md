# ADR-006: SNS fan-out to SQS for notifications

## Status

Accepted as a plan decision. The product changes are not built yet. The emulator is chosen in slice 5.

## Date

2026-10-11

## Context

Notification-service today consumes Kafka and logs work. Outbound email and SMS need retries that are separate from claim decisions.

Business problem: show a real queue-shaped workload without making SQS the claims bus.

Technical constraints: SNS topic, SQS queue, and that queue's own DLQ. Emulated, because no AWS resources are applied. Candidates are Floci, MiniStack, or LocalStack with a token. The spike is at the start of slice 5.

Scalability: notification delivery can lag without blocking approval.

Security: logs contain delivery ids and outcomes, never message content.

Cost: emulator on the VM, inside the shared 0.5 GB with the object store. No AWS queue charges.

## Decision

Notification-service publishes to SNS. An SQS queue with its own DLQ delivers and retries. Kafka remains the claims event stream. The two dead-letter mechanisms stay distinct.

## Alternatives Considered

### Kafka-only notifications

- Pros: one broker.
- Cons: the guideline's queue and pub/sub rows would be gaps, and the workload would not look like a delivery queue.
- Rejected: the emulated SNS/SQS path is an implemented row.

### SQS as the Kafka DLQ

- Pros: one place for failures.
- Cons: replay and claim ordering belong to the topic.
- Rejected: see ADR-005.

## Consequences

- Positive: delivery retries do not re-run the rule engine.
- Risks: the emulator may not match AWS. Adapter tests are the contract. If the spike fails, record the gap instead of pretending.
- Maintenance: inbox rules still apply so a redelivered notification does not send twice in effect.
- Scaling: the queue depth is a demo metric, not a production SLO.

## Implementation Plan

- Folder impact: notification-service, Compose emulator, slice 5 Jenkins stage.
- Deployment: private network only.
- Testing: DLQ on the SQS side is separate from `<topic>.DLT` tests.
- Rollback: if no emulator passes the spike, keep Kafka notification logs and update EX records. Do not create a real AWS queue.

## Teaching Section

### Architecture in simple terms

Kafka moves the claim decision. SNS tells anyone who cares. SQS is the mailbox that retries the actual send.

### Business value

The standards matrix can mark queue and pub/sub as implemented, with the word "emulated" attached honestly.

### Long-term impact

A real SNS and SQS pair can replace the emulator without changing the claims topics.

### Best practices

Never log message bodies. Never use the notification DLQ as the claims DLQ.

### Future optimization

Real AWS queues after EA approval. The spike notes which API calls were verified.
