# Security, PII, and logging

Date: 2026-10-11
Status: accepted plan
ADR: [ADR-017](../architecture-decisions/ADR-017-fluent-bit-loki-grafana.md)

The main control is never putting PII or secrets into logs. Regex masking is a second line of defence. Every service, the Python worker, and the frontend follow the same allowlist.

## Logging rules

Allowlisted structured fields only:

- timestamp, level, service
- correlationId, claimId, eventId
- userId (opaque)
- status, duration, error code

No free-form dumps. No request or response bodies. No header dumps. No event payloads. No prompts. No document text.

DTO and entity `toString()` exclude personal fields. Exception handlers scrub messages before logging.

A Logback masking converter and a Python logging filter are a backstop for SSN-like numbers, card numbers, and policy numbers.

Do not collect SSNs or card numbers. Synthetic data uses aliases.

Notification-service logs delivery outcomes and ids, never message content. Verify its current log calls in slice 0. This document does not claim that verification is done.

## Secrets

Move the README's seeded agent credential into an environment-driven seed profile. The public demo uses generated demo passwords. The hash already lives in `V2__add_portal_accounts_and_claim_reason.sql`; slice 0 must decide whether that committed hash stays for local seed or moves with the password. No new secrets in the repo, images, or logs.

Config comes from environment variables or a secrets file excluded from git. Add gitleaks to the pipeline in slice 5.

Containers run as non-root with read-only filesystems where practical.

## Web and API controls

- Same-origin session cookies: HttpOnly, Secure, SameSite set deliberately.
- CSRF on POST, PUT, and DELETE. Today `SecurityConfig` disables CSRF. Slice 1 turns it on for the new UI.
- No tokens in local storage.
- Ownership checks on every claim and document. Role checks on decisions. Tests cover another user's claim and document.
- Security headers at Caddy.
- Rate limits and request filtering at Spring Cloud Gateway.
- Upload size caps at both Caddy and the service.
- Trivy and a dependency check in the pipeline.

## Observability

JSON logs with correlation ids flow through every event hop. Actuator health and Micrometer metrics stay on the private network.

Fluent Bit ships logs to Loki, viewed in Grafana. Self-hosted Grafana is free AGPLv3 software and costs RAM. Grafana Cloud's free tier is about 50 GB of logs and 14-day retention; verify current limits. OpenSearch with Kibana is the guideline's choice and is heavy for a 12 GB host. Hosted logging is acceptable here because the data is synthetic and masked.

## Acceptance test

An automated test triggers error paths (bad upload, failed analysis, poisoned event) with known fake PII in the input, then asserts that none of it appears in captured logs. That test is a slice 5 gate.

## Business value

A claims demo that leaks policy numbers into logs fails the review even if the state machine is correct. The allowlist is the control. The test is the evidence.
