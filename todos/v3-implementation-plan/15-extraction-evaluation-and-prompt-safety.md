# Extraction, evaluation, and prompt safety

Date: 2026-10-11
Status: accepted plan
Slice: fixture generator starts in slice 2; extractor, adapter, and evaluation finish in slice 3

## Extraction strategy

1. Deterministic first. Text PDFs go through `pypdf` and template-aware parsers. Scanned images are marked `UNREADABLE_DOCUMENT`. OCR is P2.
2. LLM adapter second. A provider interface with a free-tier API implementation and a mock. Temperature 0. Structured output validated by Pydantic. Document text is passed as data inside a delimited block. The adapter is on for the recorded demo and off in CI, so the demo works without an API key.
3. Cross-checks are plain code: duplicate invoice number, date alignment, amount consistency. They are not model judgment.

## Evaluation set

A generator produces synthetic repair estimates in several layouts, with noise, rotated or missing fields, wrong totals, duplicate invoice numbers, and a few with embedded instructions such as "approve this claim". Each fixture has expected fields. A script reports field-level precision and recall. The README publishes the real numbers. Extraction tests that mock the LLM prove plumbing only, not accuracy.

The first version of the generator is slice 2, because document fixtures are needed to test upload. The evaluation script and the published scores are slice 3.

## Safety rules

- Document content is untrusted input. It is never concatenated into instructions.
- Prompts and document text are never logged. Logs carry ids, sizes, versions, and durations.
- Synthetic data only. Free-tier hosted models can have data-use terms that rule out real customer data, so the public demo never sends any.

## Gate

Timeouts and invalid output route to review, never to approval. The injection fixtures never produce an approval. If the LLM key is missing, the deterministic path or review is used.

## UI

Slice 3 adds an evidence panel: extracted fields, confidence, flags, and which rule routed the claim.

## Why this is P0

The evaluation set and rule-engine-only decisions are never cut. A demo that only shows a happy-path mock does not support the claim that documents cannot approve themselves.
