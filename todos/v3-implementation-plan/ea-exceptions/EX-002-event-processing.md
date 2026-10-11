# EA exception: event processing

Guideline row: Event processing
Status: Partly
Matrix: deferred until slice 5. Copy this file to `docs/ea-exceptions/` when that slice starts.
Date: 2026-10-11

## What the guideline asks for

ECS for long-running consumers and Lambda for short-lived work.

## What v3 does

Kafka consumers run as containers: the Java services and the Python analysis worker. A Lambda-style validator is P2 and is the first class of event-processing work to cut after the Finance and Agent workspaces and deferred Terraform.

## Justification

The claims path is a long-lived consumer with an inbox, retries, and a dead-letter topic. That shape fits a container. A separate Lambda would add an account, a deploy path, and a second runtime for a validator the cut order already marks optional.

## Cost

No Lambda invocations. Consumer RAM is inside the service and worker budgets in the hosting note.

## Alternative

Add one short-lived validator as a Lambda on the AWS target after EA approval. Do not block slices 1 through 4 on it.

## Duration

For the free demo. Revisit on the AWS target design, not during the local slice plan.
