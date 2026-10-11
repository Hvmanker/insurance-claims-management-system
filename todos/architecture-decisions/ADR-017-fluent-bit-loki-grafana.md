# ADR-017: Fluent Bit, Loki, and Grafana for logs

## Status

Accepted as a working assumption. OpenSearch remains the guideline product and is not deployed. The pipeline is slice 5. The product is not built yet.

## Date

2026-10-11

## Context

The demo needs correlation ids across claim, worker, approval, and settlement. OpenSearch is the guideline's store and is too heavy for a 12 GB host. An earlier draft marked Loki as P2. The updated plan puts Fluent Bit, Loki, and Grafana in the stack and in the P1 keep-list.

Business problem: a reviewer should open one place and see a claim's hops, and a test should prove fake PII did not arrive there.

Technical constraints: JSON logs, allowlisted fields, masking as a backstop. Fluent Bit ships to Loki. Grafana is self-hosted or Grafana Cloud's free tier (about 50 GB and 14 days; verify). Actuator stays on the private network.

Scalability: self-hosted costs about 0.5 GB. Grafana Cloud costs almost no VM RAM.

Security: hosted logs are acceptable because the data is synthetic and masked. Prompts, bodies, and document text are not logged. EX-010 records the CloudWatch and OpenSearch gap.

Cost: free software or a free tier. No CloudWatch ingestion.

## Decision

Ship JSON logs with Fluent Bit to Loki and view them in Grafana. Prefer Grafana Cloud if the free tier's current limits cover the demo and RAM is tight. Otherwise self-host. Do not deploy OpenSearch on the 12 GB host.

## Alternatives Considered

### Files on disk only

- Pros: no extra containers.
- Cons: the slice 5 gate says logs from every service reach Grafana. A folder of files fails that gate.
- Rejected.

### OpenSearch and Kibana

- Pros: the guideline pair.
- Cons: RAM. EX-010.
- Rejected for this host.

### Cut logging first

- Pros: the old P2 list.
- Cons: the updated cut order keeps the log pipeline in P1.
- Rejected.

## Consequences

- Positive: correlation ids are visible, and the log-leak test has a sink to assert against.
- Risks: a free tier can drop logs. Verify the cap and record it.
- Maintenance: new log fields need an allowlist change and a test.
- Scaling: CloudWatch can replace Loki later if the allowlist stays.

## Implementation Plan

- Folder impact: Logback JSON, Python logging filter, Fluent Bit config, Grafana, slice 5 CI.
- Deployment: private network, or outbound to Grafana Cloud from Fluent Bit only.
- Testing: bad upload, failed analysis, and poisoned event with known fake PII must not appear in captured logs.
- Rollback: if Grafana cannot fit, use the Cloud free tier before dropping the gate.

## Teaching Section

### Architecture in simple terms

Each service speaks in short labelled sentences. Fluent Bit carries them to one shelf. Grafana is how a person reads the shelf.

### Business value

The demo can show a claim id moving through services, and can show that a policy number in a bad request did not land in the log.

### Long-term impact

Central redaction on the AWS target adds a second line. The allowlist remains the first.

### Best practices

Log ids, sizes, versions, and durations. Scrub exception messages. Keep notification bodies out.

### Future optimization

OpenSearch only if the host is no longer a 12 GB box, or CloudWatch when the account exists. Either way, keep the same fields.
