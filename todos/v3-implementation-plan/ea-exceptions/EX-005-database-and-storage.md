# EA exception: database and storage

Guideline row: Database and storage
Status: Partly
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

Aurora (`aws_rds_cluster`) and S3 with KMS.

## What v3 does

One PostgreSQL instance with a schema per service. Object storage uses the S3 API against SeaweedFS or Garage, chosen by adapter tests at the start of slice 2. The AWS target description remains private S3 with KMS and lifecycle rules, and Aurora for the database. Those resources are not created.

## Justification

Aurora and KMS require an AWS account. PostgreSQL and an S3-compatible store demonstrate schema ownership, migrations, presigned uploads, and checksums on hardware the demo already has.

## Cost

Postgres is budgeted at about 1 GB. The object store shares about 0.5 GB with the AWS emulator. No Aurora or KMS charges.

## Alternative

Aurora and S3 with KMS on the AWS target. Adapter tests are the contract, because S3 compatibility is partial. Do not assume every Garage or SeaweedFS behavior exists in AWS, or the reverse.

## Duration

For the free demo. Revisit when an approved AWS account exists.
