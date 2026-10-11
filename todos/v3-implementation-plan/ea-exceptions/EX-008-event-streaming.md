# EA exception: event streaming

Guideline row: Event streaming
Status: Partly
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

Amazon MSK with multiple brokers.

## What v3 does

A single-node Kafka broker in KRaft mode, about 1 GB heap, with no replication. Producers use a transactional outbox. Consumers use an inbox, retries, and a dead-letter topic. The Kafka key is the claim id.

## Justification

MSK costs money and needs an account. One broker is enough to show ordering per claim, crash recovery of the outbox, and replay. It does not show broker failure or replication.

## Cost

About 1 GB heap on the VM. No MSK charges.

## Alternative

MSK with multiple brokers on the AWS target. The topic names, envelope, and idempotent consumers stay the same.

## Duration

For the free demo and any single-VM deployment. Do not describe this broker as highly available.
