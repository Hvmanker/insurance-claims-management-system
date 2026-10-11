# Documents and object storage

Date: 2026-10-11
Status: accepted plan
Slice: 2
ADR: [ADR-007](../architecture-decisions/ADR-007-s3-api-object-storage.md)

Each uploaded file is its own row with an opaque storage key, checksum, and lifecycle status. A claim can hold many documents, versions, and processing states. Files never travel through Kafka or PostgreSQL. Events carry references.

## Document table (claim-service schema)

| Column | Purpose |
| --- | --- |
| id, claim_id, owner_id | Identity and ownership. Every read and write checks the caller against owner_id. |
| object_key | Opaque key such as `claims/<claimId>/<uuid>`. Never the original file name. |
| original_name, content_type, size_bytes | Display and validation metadata. |
| sha256 | Integrity check on confirm, and an input to duplicate-invoice detection. |
| status | PENDING, READY, REJECTED_UPLOAD, PROCESSED, DELETED. |
| created_at, processed_at, version | Lifecycle and optimistic locking. |

Analysis output is a separate, versioned record: parser or model version, evidence spans or page references. Originals are preserved and never overwritten.

## Upload flow

1. The signed-in user requests an upload. Claim-service creates a PENDING row and returns a short-lived, scoped presigned PUT URL.
2. The browser uploads straight to the private bucket.
3. The browser calls confirm. Claim-service reads object metadata, checks size, type allowlist, and file signature, verifies the checksum, then sets READY.
4. Submit is refused unless the claim has at least one READY document.
5. A scheduled cleanup deletes PENDING rows and orphaned objects past their expiry.

## Controls

Private bucket. Expiring URLs. Byte limit per file and per claim. Bucket CORS limited to the app origin. Downloads use short-lived signed URLs issued only to the owner or an adjuster. Later roles follow the permission matrix: Agent for clients, Supervisor and SIU can view, Auditor can read, Admin and Finance do not get document bytes.

A trusted malware scanner is P2. Until then, v1 accepts only the synthetic fixture types.

## Storage backend

Code uses the plain S3 API with a configurable endpoint and path-style addressing. SeaweedFS and Garage are the candidates. Slice 2 starts by running the adapter's integration tests and then picking one. The memory-budget table in the source plan still says "slice 0" for this pick. Follow slice 2.

MinIO community edition is not an option. Its Docker images stopped in October 2025 and the repository was archived in April 2026.

The AWS target is private S3 with KMS and lifecycle rules. S3 compatibility is partial across backends, so the adapter tests are the contract.

## Gate and UI

Multiple documents per claim work. A wrong owner is denied. Oversized, wrong-type, or bad-signature uploads are refused and leave no READY row. Orphans are cleaned.

UI in this slice: an upload component with per-file status for the insured, and a document viewer for the adjuster.

## Current repo

There is no document table and no object store in Compose.
