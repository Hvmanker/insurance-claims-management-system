# Risks, cut order, and interview narrative

Date: 2026-10-11
Status: accepted plan

If time runs short, cut from the bottom of the priority list and keep the top. The P0 items are what make the project credible. They are never cut.

## Priority and cut order

| Priority | Items |
| --- | --- |
| P0: never cut | State machine and decision table. Shared outbox and inbox. Browser end-to-end test. Role-based access control with an audit trail. Evaluation set and rule-engine-only decisions. |
| P1: keep if at all possible | Supervisor, SIU, Auditor, and Admin workspaces. Documents flow. Python worker with deterministic extraction. Retry, DLT, and replay. Real Jenkinsfile. Redis sessions, cache, rate limits, and locks. Fluent Bit, Loki, and Grafana. PII allowlist and log-leak test. README alignment matrix. |
| P2: cut first, in this order | Finance and Agent workspaces. Terraform (already deferred). Lambda-style validator. Real cron scheduling of the batch job. Malware scanner. OCR. Public VM deployment, falling back to local Compose plus a labelled preview. |

## Main risks

| Risk | Mitigation |
| --- | --- |
| Free VM unavailable, shrunk, or reclaimed | Local Compose is the primary demo. Labelled preview is the fallback. |
| Four JVMs, the gateway, the Next.js server, and Kafka exceed 12 GB | Measure in slice 1, not slice 8. Lower heaps. Merge the quietest services only as a last resort. |
| Emulator gaps (SNS, SQS, S3) | Spike at the start of slice 5. Adapters behind interfaces. Contract tests. |
| Scope creep | Gates must pass before the next slice. The cut order above is binding. |
| Hidden defects in the existing repo | Slice 0 audit before changing anything. |
| LLM free-tier limits or data terms change | Deterministic path works without a key. Synthetic data only. |
| Redis outage or data loss | PostgreSQL stays authoritative. Cache and state are separate. Every use has a fallback. Outage tests in slice 4. |

## Interview narrative

"I extended an existing Spring Boot and Kafka claims system with the owner's permission. A Python worker extracts evidence from synthetic repair estimates, but Java rules decide, and a document cannot talk its way into an approval. Events use a transactional outbox with idempotent consumers, retries, and a replayable dead-letter topic. I mapped every row of the company stack guideline to what runs for free, what is emulated, and what is a documented gap with an exception record. I applied no AWS resources because new AWS accounts are credit-based now, and I recorded infrastructure as code as a documented gap."

Add two measured numbers before presenting: peak memory on the VM, and field-level extraction accuracy on the evaluation set.

## Business reading

P0 is the credibility of the claims decision. P1 is the operational story a reviewer can click through. P2 is extra products and extra infrastructure. Cutting P2 still leaves a demo that shows rules, evidence, roles, and durable events.
