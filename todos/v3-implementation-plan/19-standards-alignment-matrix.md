# Standards alignment matrix

Date: 2026-10-11
Status: deferred until slice 5. Stack choices in this table are changeable.
Related: [ea-exceptions/](ea-exceptions/)

Nothing in slices 0 to 4, or in slices 6 and 7, depends on this section. Revisit it when slice 5 starts. The free demo is an engineering showcase. It is not a compliant deployment of the company standard.

Each Gap or Partly row has an exception record. Implemented rows stay here only.

| Guideline row | Free demo implementation | AWS target | Status |
| --- | --- | --- | --- |
| Web: Next.js | Current stable Next.js (App Router, server components) as a Node container | Amplify Hosting (supports SSR) | Implemented |
| APIs: Java + Spring Boot | Four containerized services | ECS tasks behind API Gateway | Implemented |
| GenAI and agents: Python | FastAPI analysis worker, LLM adapter | ECS service | Implemented |
| Batch development | Containerized Spring Batch job that expires stale MORE_INFO_REQUESTED claims | AWS Batch job | Implemented |
| Batch scheduling | Cron container | EventBridge to AWS Batch to CloudWatch | Gap: documented |
| Event processing | Containerized Kafka consumers. One Lambda-style validator is P2 | ECS; Lambda for short-lived work | Partly |
| Cloud and API hosting | OCI Arm VM with Compose | ECS Fargate | Gap: documented |
| API entry point | Spring Cloud Gateway behind Caddy (HTTPS, same origin) | API Gateway | Partly (equivalent behavior, different product) |
| Database and storage | Postgres; S3 API on SeaweedFS or Garage | Aurora (`aws_rds_cluster`), S3 with KMS | Partly |
| Web hosting | Next.js server container behind Caddy | Amplify | Gap |
| Identity | Spring sessions and roles, config seam for OIDC | Cognito | Gap |
| Queue and async | SNS to SQS for notification delivery, emulated | SQS | Implemented (emulated) |
| Pub/sub and notifications | SNS topic, emulated | SNS | Implemented (emulated) |
| Event streaming | Single-node Kafka | MSK, multi-broker | Partly |
| Jenkins mandatory | Real Jenkinsfile, run on demand, same `make ci` as Actions | Organization Jenkins with gates | Implemented (personal scope) |
| IaC mandatory | Version-controlled Compose files and a deploy script. Terraform deferred. | Terraform through the DevOps process | Gap (deferred) |
| Logging and observability | JSON logs and correlation ids. Fluent Bit to Loki and Grafana (self-hosted or Grafana Cloud free tier). Basic metrics. | CloudWatch, Fluent Bit, OpenSearch | Partly |
| Data protection in logs | Allowlisted fields, no payload logging, tests | Same, plus central redaction | Implemented |
| Stateless, containerized, least privilege | Stateless services (sessions in Redis), env config, non-root containers | ECS with IAM roles; ElastiCache for Redis | Implemented |

## Emulator caveat

SNS and SQS run on an AWS emulator. LocalStack's free image needs an account and token. MIT-licensed alternatives are young. Slice 5 starts with a spike to confirm the chosen emulator handles the SNS, SQS, and S3 calls this project makes. The object-store product for the app's own bucket is chosen in slice 2 and may differ from the emulator.

## EA records

Copy [ea-exceptions/](ea-exceptions/) to `docs/ea-exceptions/` when slice 5 writes the records into the product tree. Each record states justification, cost, alternative, and duration.
