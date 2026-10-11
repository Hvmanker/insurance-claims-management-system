# ADR-016: Compose and a deploy script, without Terraform

## Status

Accepted as a plan decision. IaC is a documented gap (EX-009). Terraform may be added later. The product is not built yet.

## Date

2026-10-11

## Context

The guideline says infrastructure as code is mandatory. An earlier PDF draft applied OCI Terraform and validated an AWS module without applying it. The updated plan drops Terraform to keep scope on the claims product. New AWS accounts are credit-based, so an applied AWS module is also a cost risk.

Business problem: a stranger can still run the demo, and the VM steps are written down, without a second infrastructure project.

Technical constraints: version-controlled Compose files, a deploy script, and manual setup steps. Jenkins validates Compose config. It does not run `terraform apply`.

Scalability: the VM is a single host. This ADR does not design multi-region.

Security: the deploy script must not contain secrets. Images are pinned. Arm images are built in CI.

Cost: no Terraform Cloud and no AWS resources from this decision.

## Decision

Do not add Terraform in v3. Describe the runnable system with Compose and a deploy script. Record the guideline gap in EX-009. Putting Terraform back is a new ADR, not a silent extra in slice 5.

## Alternatives Considered

### AWS Terraform, validate only

- Pros: closer to the old PDF and to the guideline.
- Cons: design time, and a module that exists will eventually be applied by mistake.
- Rejected in this plan.

### OCI Terraform applied

- Pros: a real IaC run on the free VM.
- Cons: the updated plan chose written steps instead, and free-tier APIs move.
- Rejected.

## Consequences

- Positive: slice 5 stays about Jenkins, logging, Redis hardening, and the batch job.
- Risks: manual VM steps drift. The steps have to be good enough that slice 8's stranger can follow them.
- Maintenance: Compose is the source of truth for ports, memory limits, and images.
- Scaling: when Terraform returns, it should generate or wrap this Compose contract, not invent a second topology.

## Implementation Plan

- Folder impact: Compose files, `deploy` script, docs for manual VM setup, Jenkins Compose validation, EX-009.
- Deployment: slice 5 writes the script. Slice 8 uses it if a VM exists.
- Testing: `make ci` includes Compose config validation. A failed stage must be able to fail the build.
- Rollback: local `docker compose up` does not need the script.

## Teaching Section

### Architecture in simple terms

The recipe is the Compose file. The cloud blueprint is postponed on purpose, and the postponement is written down.

### Business value

Time goes to the outbox, the rules, and the roles. The interview line says infrastructure as code is a gap, not a hidden Terraform folder.

### Long-term impact

A later Terraform ADR can supersede this one without rewriting the services.

### Best practices

Pin images. Keep secrets out of the script. Do not create AWS resources from the deploy path.

### Future optimization

Terraform through the DevOps process after EA approval and a budget. Supersede this ADR rather than editing history.
