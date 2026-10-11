# ADR-008: Defer hosting, and keep the AWS target documentary

## Status

Accepted as deferred. Local Compose is the only target until slice 8. No AWS resources are applied.

## Date

2026-10-11

## Context

The company guideline is an AWS stack. New AWS accounts are credit-based: up to about $200, ending after six months or when credits run out. Those figures come from secondary sources and must be verified before anyone relies on them. Oracle Always Free Arm capacity is reported smaller and reclaimable. The demo still needs a story about the AWS target.

Business problem: show the guideline mapping without a bill and without claiming the free demo is compliant.

Technical constraints: sections 7 and 8 of the plan are deferred. Slice 5 revisits the matrix. Slice 8 decides the VM.

Scalability: a single VM is not multi-region. Multi-region is a non-goal.

Security: only Caddy is public if a VM exists. No accidental AWS resources.

Cost: zero AWS. OCI only if an Always Free shape is actually available.

## Decision

The guaranteed deliverable is local Compose. A public OCI VM is optional in slice 8 and must pass a measurement gate. The AWS target is documented in the alignment matrix and the EA exceptions. It is not applied. If the VM is missing, publish a labelled frontend-only preview and do not imply the backend is live.

## Alternatives Considered

### Apply an AWS Terraform module

- Pros: closer to the guideline.
- Cons: credit-based accounts and a standing bill risk. The plan withdrew Terraform. See ADR-016.
- Rejected.

### Depend on Oracle Always Free from slice 0

- Pros: a public URL earlier.
- Cons: capacity and reclaim risk. The slice plan does not need it.
- Rejected: decide in slice 8.

## Consequences

- Positive: the demo runs on a laptop if every cloud free tier disappears.
- Risks: a public URL may never exist. The README must say so.
- Maintenance: two measured numbers at the end: peak memory, and extraction accuracy.
- Scaling: the AWS target remains a translation guide, not a second environment to keep warm.

## Implementation Plan

- Folder impact: README in slice 8, Compose memory limits, exception records.
- Deployment: local until slice 8. Arm images built in CI, not on the VM.
- Testing: VM restart test if a VM exists; otherwise the Compose restart equivalent.
- Rollback: drop the VM and keep Compose. The preview must be labelled.

## Teaching Section

### Architecture in simple terms

The laptop demo is the product. The cloud diagram is the map for later, with the gaps written down.

### Business value

Honesty about cost is part of the interview narrative. No AWS resources were applied.

### Long-term impact

When a funded account exists, the matrix says what to build. The slice plan does not change to get there.

### Best practices

Measure in slice 1. Set quota alerts. Never create AWS resources to "try the free tier".

### Future optimization

ECS, Aurora, MSK, and Amplify after EA approval and a budget. Update the exceptions when that happens.
