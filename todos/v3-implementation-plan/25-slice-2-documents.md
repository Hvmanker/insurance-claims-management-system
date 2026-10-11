# Slice 2: Documents

Length: 4 to 5 days
Depends on: slice 1 gate
UI: upload component with per-file status for the insured; document viewer for the adjuster

## Work

- Document table, presigned upload, confirm with checksum, file signature, and size checks.
- Object-store adapter with integration tests. Pick an S3-compatible store at the start of this slice. SeaweedFS and Garage are the candidates. MinIO community edition is excluded.
- Ownership checks and signed downloads.
- Orphan cleanup job.
- Synthetic fixture generator, first version.

## Gate

Multiple documents per claim work. A wrong owner is denied. Oversized or invalid files fail. Orphans are cleaned.

## Tests

- Another user's document is denied on read and mutate.
- Oversized, wrong-type, or bad-signature upload is refused and leaves no READY row.
- Submit without a READY document is refused.

## Spike note

The hosting memory table mentions choosing lightweight storage in slice 0. This slice owns the choice. Record the winner and the adapter tests that justified it.
