# Slice 5: Standards layer

Length: 4 to 5 days
Depends on: may move earlier or later. It does not depend on slices 6 or 7.
UI: none

Platform work. Stack choices (emulator, logging, CI) are working assumptions and can change. Revisit [19-standards-alignment-matrix.md](19-standards-alignment-matrix.md) at the start.

## Work

- Short spike to pick the AWS emulator, then SNS to SQS notification delivery with its own DLQ.
- Redis hardening: password and ACLs per service, TLS option, maxmemory and eviction policy, separate cache and state roles, persistence for the state role, metrics to Grafana, and gateway rate limiting backed by Redis.
- Spring Batch job that expires stale MORE_INFO_REQUESTED claims.
- Real Jenkinsfile: build, test, lint, security scan, image build, Compose config validation. Shared `make ci` with GitHub Actions.
- Compose files and a deploy script for the VM, plus written manual setup steps. Terraform stays deferred and is recorded as an EA exception.
- JSON logging, PII allowlist, masking backstop, log-leak test, Fluent Bit shipping to Loki and Grafana.
- gitleaks, Trivy, and dependency checks in the pipeline.
- EA exception records for each gap, copied from [ea-exceptions/](ea-exceptions/) into `docs/ea-exceptions/` and updated if the spike changed a choice.

## Gate

Every Jenkins stage runs a real command and can fail. Logs from every service reach Grafana with correlation ids. The log-leak test passes.

## Tests

- Known fake PII on error paths never appears in captured logs.
- A stale MORE_INFO_REQUESTED claim moves to EXPIRED.
- Rate limits fail open with an alert when Redis is down, once this slice adds them.

## Emulator candidates

Floci, MiniStack, or LocalStack with a free account token. Confirm SNS, SQS, and the S3 calls you actually make. The application bucket may already be SeaweedFS or Garage from slice 2. The emulator spike must not silently replace that choice.
