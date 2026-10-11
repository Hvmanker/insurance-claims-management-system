# Executive summary

Date: 2026-10-11
Status: plan, not implemented

v3 extends this repository into one auto-insurance claims product on synthetic data. The owner has given permission to extend the repo. The four existing service boundaries stay: claim, approval, settlement, and notification. A Python analysis worker is added beside them. A free single-VM demo and a separately documented AWS target are both described. The local `docker compose up` run is the guaranteed demo.

## What is binding

Two things are commitments:

- The build order in slices 0 through 8, about 8 to 10 weeks part-time.
- The role-based UI requirements: eight roles, permission checks on the server, and a screen set for each role.

Every slice that adds a business capability also ships the UI for that capability. The first browser journey works at the end of slice 1.

## What can change later

Stack and hosting rows are working assumptions. Changing Next.js, the object store, the AWS emulator, or the VM provider does not require a new slice plan. The standards alignment matrix is deferred until slice 5. The free-hosting decision is deferred until slice 8.

## Product shape

An insured person or an agent files an auto damage claim with repair-estimate documents. A worker extracts facts, confidence, and non-binding flags. Java rules in approval-service decide. Auto-approval happens only when every safety rule passes. Rejection is always a human action. Settlement writes a simulated record. No money moves.

A real claims desk also needs supervisors, investigators, finance officers, auditors, and administrators. Those workflows are slice 6 in the API and slice 7 in the UI. They extend the state machine. They do not replace it.

## Why extend this repo

A rewrite would cost one to two weeks and throw away a working baseline: Spring sessions, BCrypt, PostgreSQL schemas per service, Kafka, and a portal. The plan records that permission in the README during slice 0.

## Timeline

| Slice | Focus | Length |
| --- | --- | --- |
| 0 | Baseline and decisions | 1 to 2 days |
| 1 | Thin end-to-end, Insured and Adjuster | about 1 week |
| 2 | Documents | 4 to 5 days |
| 3 | Analysis worker | 4 to 5 days |
| 4 | Reliability | 3 to 4 days |
| 5 | Standards layer (may move) | 4 to 5 days |
| 6 | Enterprise roles and workflow | 6 to 8 days |
| 7 | Enterprise UI | 8 to 10 days |
| 8 | Deploy and demo | 3 to 4 days |

## Business value

The demo shows a claims operation a reviewer can walk through: evidence in, rules decide, humans reject, events survive a crash, and every role sees only its own work. The AWS mapping is honest about what runs for free, what is emulated, and what is a gap with an exception record.
