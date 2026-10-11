# EA exception: infrastructure as code

Guideline row: IaC mandatory
Status: Gap (deferred)
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

Terraform applied through the DevOps process.

## What v3 does

Version-controlled Compose files, a deploy script, and written manual steps for the VM. Terraform is not written and not applied. It can be added later. It is also on the P2 cut list, already deferred, so cutting it changes nothing in the current plan.

## Justification

An AWS Terraform module that is applied would create billable resources on a credit-based account. A module that is only validated still costs design time the plan chose to spend on the claims workflow. Compose is the artifact that actually runs the demo.

## Cost

No Terraform Cloud or AWS resource cost. The cost is a manual VM setup that must be written clearly enough for slice 8.

## Alternative

Terraform for the AWS target after EA approval and a funded account. OCI Terraform is not in this plan either. An earlier PDF draft applied OCI Terraform and linted an AWS module. This Markdown plan withdraws that draft.

## Duration

Until a later decision adds Terraform. The exception stays while the demo is defined by Compose.
