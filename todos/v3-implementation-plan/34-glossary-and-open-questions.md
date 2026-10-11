# Glossary and open questions

Date: 2026-10-11

## Glossary

| Term | Meaning in this plan |
| --- | --- |
| Adjuster | Claims handler who decides within an authority limit. |
| Agent | External broker. Own book only. No decisions. Today's code uses AGENT for the person who decides. Slice 0 maps that name. |
| DLT | Dead-letter topic on Kafka, named `<topic>.DLT`. Not the SQS DLQ. |
| DLQ | The SQS queue's own dead-letter queue for notification delivery. |
| Human gate | REVIEW_READY. System paths that fail a safety rule stop here. |
| Inbox | Consumer dedupe row written in the same transaction as the side effect. |
| Outbox | Row written in the same transaction as business state, published later. |
| Read model | Claim-service's copy of status for the UI, updated from events. |
| Rule version | The approved configuration a decision used. Stored on the decision. |
| SIU | Special investigation unit. Records findings. Cannot approve, reject, or settle. |
| Simulated settlement | A settlement row with no money movement. The UI says so. |
| Working assumption | A stack or hosting choice that can change without a new slice plan. |

## Open questions the plan leaves to a slice

| Question | When it is decided |
| --- | --- |
| What today's AGENT role maps to | Slice 0 audit |
| Exact core state enum if the embedded diagram counts SIMULATED_SETTLED inside the nine | Slice 0, before the migration |
| SeaweedFS or Garage | Start of slice 2, by adapter tests |
| Floci, MiniStack, or LocalStack | Start of slice 5 |
| Redis 8 or Valkey | When deployment is decided |
| Self-hosted Grafana or Grafana Cloud free tier | Slice 5, after a RAM check |
| Domain or tunnel for HTTPS | Slice 8 |
| OCI VM or local-only plus a labelled preview | Slice 8, after measuring capacity |
| Whether Finance and Agent workspaces ship | Only if P2 is cut |

## Wording mismatch to keep visible

The VM memory table says to pick lightweight object-store and emulator options in slice 0. The delivery plan picks the object store at the start of slice 2 and the emulator at the start of slice 5. The slice plan is the one to follow.

## Source

Insurance Claims Platform v3 Implementation Plan, Markdown, 11 Oct 2026, @Himank. The earlier PDF is not the source for frontend, Redis, gateway, Terraform, logging, role count, or slice count.
