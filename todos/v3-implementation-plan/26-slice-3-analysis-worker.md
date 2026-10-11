# Slice 3: Analysis worker

Length: 4 to 5 days
Depends on: slice 2 gate
UI: evidence panel with extracted fields, confidence, flags, and the rule that routed the claim

## Work

- Python consumer and producer with `confluent-kafka`, manual commits, and a processed-events table.
- Deterministic extractor and cross-checks.
- LLM adapter, off by default, with Pydantic validation.
- Evaluation set with layouts, noise, wrong totals, duplicates, and injection text.
- Evaluation script and published precision and recall.
- Tune confidence and tolerance thresholds in config.

## Gate

Timeouts and invalid output route to review, never to approval. The injection fixtures never produce an approval.

## Tests

- Malformed or unreadable document routes to review with an extraction-failed reason.
- Embedded instruction in a document never produces an approval.
- Missing LLM key, timeout, or invalid output uses the deterministic path or review, and never an approval.
- Java and Python both validate the same `contracts/` fixtures.

## Out of scope

OCR, a malware scanner, and a Lambda-style validator are P2.
