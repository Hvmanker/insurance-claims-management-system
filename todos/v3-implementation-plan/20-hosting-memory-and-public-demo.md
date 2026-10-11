# Hosting, memory, and the public demo

Date: 2026-10-11
Status: deferred until slice 8
ADR: [ADR-008](../architecture-decisions/ADR-008-deferred-hosting-and-aws-target.md)

Until slice 8, the local Compose run is the only target. A public OCI VM is a bonus that must pass a measurement gate. Nothing in the project depends on a free tier remaining available.

## Free options as of October 2026

These figures come from search results, not from the providers' own pages. Treat them as approximate until someone verifies them.

- AWS is credit-based for new accounts: up to $200 in credits. The Free Plan ends after 6 months or when credits run out. It cannot host this demo for free over time. Do not create AWS resources by accident. The plan applies none.
- Oracle Always Free was reported cut from 4 OCPUs and 24 GB to 2 OCPUs and 12 GB for Arm instances. Idle instances can be reclaimed. Arm capacity is often unavailable. Confirm current limits and region capacity on Oracle's pages before committing.
- LocalStack's free image requires an account and auth token.

## VM memory budget

12 GB is assumed. Measure it. Do not trust the table.

| Component | Starting limit | Note |
| --- | --- | --- |
| Four Spring Boot JVMs plus Spring Cloud Gateway | about 0.5 GB heap each; 256 MB for the quieter ones | RSS is often 0.7 to 0.9 GB each |
| Kafka (KRaft, one broker) | about 1 GB heap | No replication, single host |
| PostgreSQL | about 1 GB | Schema-per-service in one instance |
| Python worker | about 0.5 GB | LLM calls are remote |
| Next.js server | about 0.3 GB | Measure under load |
| Fluent Bit, Loki, and Grafana | about 0.5 GB if self-hosted | Near zero on the VM if Grafana Cloud's free tier is used |
| Object store and AWS emulator | about 0.5 GB combined | Choose lightweight options. The slice plan picks the object store in slice 2 and the emulator in slice 5. The source table says "slice 0". Follow the slice plan. |
| Redis (cache and state) | about 0.25 GB, maxmemory 128 to 256 MB | Separate instances in production |
| OS, page cache, headroom | about 4 GB | Required on one host |

Set Compose memory limits from day one. Today's claim-service heap is `-Xmx192m`, which is already under the 256 MB quiet-service note, but RSS is not measured.

In slice 1, measure whether four JVMs, the gateway, Next.js, and Kafka fit. Do not wait for slice 8 to discover they do not. Lower heaps first. Merge the quietest services only as a last resort.

In slice 8, record RSS, startup time, disk growth, and event lag under a small load, and publish the numbers. Build Arm images in GitHub Actions and pull pinned images onto the VM. Do not build on two cores.

## Public-demo controls

- Caddy needs a domain for automatic certificates. Secure cookies need HTTPS. Choose a free subdomain or a tunnel in slice 8.
- No open signup. Seeded demo accounts or an invite code. Per-user upload quotas. Rate limits on upload and submit. Nightly reset of demo data.
- Only Caddy is exposed.
- Encrypted Postgres and object backups to free object storage if the allowance fits, with one restore rehearsal documented.
- If a VM is unavailable, keep the full local demo and publish a clearly labelled frontend-only preview with sample data. Never imply the backend is live.
- Always Free shapes only, free plan only, quota alerts. No accidental AWS resources.

## ADR note

[ADR-016](../architecture-decisions/ADR-016-compose-deploy-script-not-terraform.md) covers how the VM is described: Compose, a deploy script, and written steps. Terraform is deferred.
