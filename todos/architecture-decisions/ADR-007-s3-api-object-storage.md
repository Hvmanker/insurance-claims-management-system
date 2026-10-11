# ADR-007: S3 API object storage, chosen in slice 2

## Status

Accepted as a plan decision. The product is not built yet. The concrete server (SeaweedFS or Garage) is chosen at the start of slice 2.

## Date

2026-10-11

## Context

Claims need many documents, versions, and processing states. File bytes must not sit in PostgreSQL or Kafka. MinIO community edition is not available: images stopped in October 2025 and the repository was archived in April 2026.

Business problem: an adjuster must see the repair estimate the worker read, and a second user must not.

Technical constraints: plain S3 API, configurable endpoint, path-style addressing. Presigned PUT and GET. Private bucket. CORS limited to the app origin.

Scalability: objects are keyed `claims/<claimId>/<uuid>`. The original file name is metadata, not the key.

Security: checksum, type allowlist, file signature, size caps. Scanner is P2. v1 accepts synthetic fixture types only.

Cost: about 0.5 GB shared with the emulator. AWS S3 with KMS is the target and is not created.

## Decision

Store files through the S3 API. Pick SeaweedFS or Garage by running the adapter integration tests at the start of slice 2. Approval and the worker receive references, not bytes.

## Alternatives Considered

### MinIO

- Pros: familiar.
- Cons: community edition is archived.
- Rejected.

### Bytes in PostgreSQL

- Pros: one datastore.
- Cons: backups, size, and event payloads.
- Rejected: events carry references.

### Choose the store in slice 0

- Pros: the memory table mentions slice 0.
- Cons: the delivery plan assigns the choice to slice 2, when the tests exist.
- Rejected: follow the slice plan.

## Consequences

- Positive: upload path matches production shape, including confirm-after-PUT.
- Risks: S3 compatibility is partial. Adapter tests are the contract.
- Maintenance: orphan cleanup for PENDING rows past expiry.
- Scaling: the AWS move is a new endpoint and KMS, after the tests pass against that endpoint too.

## Implementation Plan

- Folder impact: claim-service document table, adapter, browser upload, worker reader.
- Deployment: private Compose network.
- Testing: bad signature, wrong owner, unavailable store, orphan cleanup.
- Rollback: slice 2 gate fails closed. Do not submit claims that require documents until the gate passes.

## Teaching Section

### Architecture in simple terms

The database remembers the file's id and hash. The bucket holds the bytes. The browser talks to the bucket with a short-lived key.

### Business value

Ownership and integrity are visible in the demo: the wrong person cannot download, and a corrupt upload never becomes READY.

### Long-term impact

KMS and lifecycle rules land on the AWS target without a new upload protocol.

### Best practices

Opaque keys. Verify checksum on confirm. Never overwrite originals.

### Future optimization

A malware scanner when P2 is funded. OCR stays P2; unreadable scans are flagged, not guessed.
