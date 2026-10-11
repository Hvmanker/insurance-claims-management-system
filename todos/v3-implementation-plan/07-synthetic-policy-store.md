# Synthetic policy store

Date: 2026-10-11
Status: accepted plan
Slice: 1 for the table and submit check; slice 6 for admin maintenance and cache

The repo has no policy store. `claims.policy_number` is a string the caller sends. v3 adds a synthetic `policies` table in the claim-service schema, seeded by a Flyway migration.

## Columns the plan names

- Policy number
- Holder alias
- Coverage start and coverage end
- Deductible
- Coverage limit
- Status

Use aliases. Do not collect SSNs or card numbers.

## Submit-time check

Claim-service validates the policy when the claim is submitted. It puts a minimal snapshot on the `claim.submitted` event: coverage window and limit. Approval-service uses that snapshot. It never queries the claim database.

That snapshot is how rule 2 can say the policy was not valid on the date of loss without a cross-service query.

## Seeds

Slice 0 defines synthetic personas for all eight roles, including agent-to-client links. Slice 1 loads policies those personas can file against. Slice 6 uses the agent-to-client links for book scoping, and admin can maintain policy data.

## Cache

From slice 6, policy lookups may be cached in Redis. A cache miss or a Redis outage reads PostgreSQL. Event-driven eviction plus a short TTL keep the cache from outliving a policy change. The table remains authoritative.

## Business value

A policy check is the first safety rule a reviewer expects. A snapshot on the event keeps that check without coupling approval-service to the claim schema.
