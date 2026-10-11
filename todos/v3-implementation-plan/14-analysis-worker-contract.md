# Analysis worker contract

Date: 2026-10-11
Status: accepted plan
Slice: stub in slice 1, real worker in slice 3
ADR: [ADR-004](../architecture-decisions/ADR-004-java-rules-decide.md)

The worker extracts document facts with evidence and confidence and raises non-binding flags. It never approves, rejects, or pays. Java rules make every decision, so a document cannot talk its way into an approval.

## Contract

The worker consumes `claim.submitted`, reads the READY documents from storage, and publishes `claim.analysis.completed` with:

- extracted `invoice_number`, `provider_name`, `date_of_service`, line items, and `billed_amount` as a decimal string with currency;
- per-field confidence and evidence (page and text span);
- non-binding flags such as `AMOUNT_MISMATCH`, `DATE_MISALIGNED`, `DUPLICATE_INVOICE_NUMBER`, `LINE_ITEM_OUTLIER`, `UNREADABLE_DOCUMENT`, `SUSPECTED_INSTRUCTION_IN_DOCUMENT`;
- the parser and model version.

There is no `AUTO_APPROVE` and no authoritative `risk_score` in the payload. Invalid, late, or missing output sends the claim to `REVIEW_READY` with `ANALYSIS_FAILED`.

## Runtime

- Python consumer and producer.
- `confluent-kafka` with manual commits.
- Idempotent processing and a processed-events table.
- `kafka-python` is avoided as poorly maintained.
- FastAPI is the service shape named in the standards matrix.
- The worker is internal to the Compose network.

## Slice 1 stub

A stub consumer returns fixed facts so the rule engine, settlement, and UI can be demonstrated before extraction exists. The stub must still publish a valid `claim.analysis.completed` fixture from `contracts/`.

## Current repo

Approval-service consumes `claim.submitted` directly and decides from the estimated amount. v3 inserts the worker between claim-service and approval-service.

## Business value

The split is the interview point: extraction can be wrong, slow, or hostile, and the claim still waits for rules and a human.
