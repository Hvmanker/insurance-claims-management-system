# EA exception: logging and observability

Guideline row: Logging and observability
Status: Partly
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

CloudWatch, Fluent Bit, and OpenSearch.

## What v3 does

JSON logs and correlation ids. Fluent Bit ships them to Loki. Grafana is the viewer, either self-hosted or on Grafana Cloud's free tier (about 50 GB and 14-day retention; verify the current limit). Actuator and Micrometer stay on the private network. OpenSearch is not deployed because it is heavy for a 12 GB host.

## Justification

CloudWatch and OpenSearch need an AWS account, and OpenSearch does not fit the memory budget. Loki and Grafana still give a central place to see correlation ids across services. Hosted Grafana is acceptable because the data is synthetic and masked.

## Cost

About 0.5 GB if self-hosted, or near zero VM RAM if Grafana Cloud's free tier is used. No CloudWatch ingestion charges. Confirm the free-tier cap before the demo so logs are not dropped silently.

## Alternative

CloudWatch plus OpenSearch on the AWS target, still with the same allowlisted fields. Central redaction is the extra control that target adds. The log-leak test remains required here.

## Duration

For the free demo. Revisit if the host grows past 12 GB or the demo moves to AWS.
