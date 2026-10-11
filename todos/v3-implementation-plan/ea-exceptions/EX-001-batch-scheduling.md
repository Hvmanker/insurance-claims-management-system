# EA exception: batch scheduling

Guideline row: Batch scheduling
Status: Gap
Matrix: deferred until slice 5. Copy this file to `docs/ea-exceptions/` when that slice starts.
Date: 2026-10-11

## What the guideline asks for

EventBridge invokes AWS Batch, and CloudWatch observes the run.

## What v3 does

A containerized Spring Batch job expires stale `MORE_INFO_REQUESTED` claims. A cron container starts it. Real cron scheduling of that job is P2 and may be cut. The job itself is in slice 5 and is not a cut candidate under P0.

## Justification

The free demo has no AWS account resources. EventBridge and AWS Batch would require a billed or credit-based account. The business need is a timeout on one claim state, which a containerized job satisfies.

## Cost

No AWS spend. One small container on the VM. Operator time to document the schedule.

## Alternative

EventBridge to AWS Batch to CloudWatch on the AWS target, when a real account and EA approval exist. Until then, the cron container is the schedule.

## Duration

For the life of the free demo. Revisit if the demo moves to an AWS account or if P2 is restored and a real scheduler is required.
