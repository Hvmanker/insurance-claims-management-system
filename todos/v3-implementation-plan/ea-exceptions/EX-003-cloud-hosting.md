# EA exception: cloud and API hosting

Guideline row: Cloud and API hosting
Status: Gap
Matrix: deferred until slice 5. Hosting itself is decided in slice 8.
Date: 2026-10-11

## What the guideline asks for

ECS Fargate.

## What v3 does

Local Docker Compose is the guaranteed host. An OCI Always Free Arm VM is a bonus if capacity exists in slice 8. The VM is set up from written steps plus Compose. No ECS resources are created.

## Justification

New AWS accounts are credit-based. Credits end, and the demo must keep running without a bill. The plan forbids accidental AWS resources.

## Cost

Zero AWS. OCI Always Free only if a VM is actually provisioned, and only shapes on the free plan. Local Compose costs a developer machine.

## Alternative

ECS Fargate on the documented AWS target, after EA approval and a funded account. The Compose files are the workload definition to translate later.

## Duration

For as long as the demo is free. Slice 8 records whether a VM was used or the fallback is local Compose plus a labelled frontend preview.
