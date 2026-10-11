# ADR-018: Per-role authority limits on claim decisions

## Status

Accepted as a plan decision. Phase 0 records the rules only. **Enforcement lands in slice 6A** (approval-service). Until then, the legacy AGENT may decide any SUBMITTED claim with no limit check; that gap is expected and is documented in the verified-behavior note.

## Date

2026-10-11

## Context

v3 adjuster decisions must respect configured monetary limits. Amounts above a role's limit must not be silently approved or clipped. Supervisors handle escalations. Segregation-of-duties checks (own claim, own policy) must run before limit math. Every denial and every applied limit must be auditable.

Business problem: demonstrate that the platform enforces delegation of authority the way a claims operation expects, not only on approve but also on reject.

Technical constraints: approval-service owns decision transitions and limit enforcement. One ISO-4217 currency for comparison; no FX. Amounts use `BigDecimal` with scale 2 and `HALF_EVEN` rounding. Reject and approve share the same limit gate.

Security: over-limit attempts return **HTTP 403** with coded reason **`AUTHORITY_LIMIT_EXCEEDED`** and move the claim to **`ESCALATED`**. Only **SUPERVISOR** may decide claims in `ESCALATED`. Own-claim and own-policy checks run **first**; every denial is audited.

Scalability: limits are per role and loaded from configuration (versioned in slice 6C with a second approver).

Cost: no external rules engine; limits live beside the decision table in approval-service.

## Decision

1. **Enforcing service:** **approval-service** evaluates authority limits on **approve** and **reject** only. **Request-more-info is not limit-gated** (SoD checks still apply to all three decision types).
2. **Compared amount:** use the **approved amount when present**, otherwise **`estimatedAmount`** (today's field on the claim entity). This is the same monetary value used for auto-approval routing and adjuster limit comparison in contracts and the read model.
3. **Currency:** all limit comparisons use **`USD`** (ISO-4217). **No foreign-exchange conversion** in limit checks.
4. **Decimal rules:** **`BigDecimal` only**, scale **2**, rounding mode **`HALF_EVEN`** for any normalization before compare.
5. **Over limit:** refuse the decision with **403** and reason code **`AUTHORITY_LIMIT_EXCEEDED`**, and transition the claim to **`ESCALATED`** (slice 6A state; see ADR-009).
6. **Escalation decisions:** only **SUPERVISOR** may approve, reject, or otherwise decide claims in **`ESCALATED`**.
7. **No clipping:** the system **never reduces** the claim amount to fit a limit. The full assessed amount stays on the claim; the actor must escalate or a supervisor must act.
8. **Reject gated like approve:** an adjuster **cannot reject** above their authority limit any more than they can approve; both paths run the same limit comparison and escalation behavior.
9. **Order of checks:** **own-claim** and **own-policy** segregation run **before** authority-limit comparison. Any denial (SoD or limit) is **audited** with actor, outcome, and reason code.
10. **Audit on success:** every decision that proceeds stores the **actor**, the **applied limit** (or explicit "no limit" for supervisor escalation resolution), and the **rule/limit configuration version** in the append-only audit trail.
11. **Phase:** slice **6A** implements enforcement. Phase 0 is documentation only.

## Alternatives Considered

### Clip amount to the adjuster's limit and approve partial authority

- Pros: fewer escalations.
- Cons: misstates the loss; unusable for audit and reinsurance.
- Rejected.

### Enforce limits in claim-service on read

- Pros: single place for "can this user see it."
- Cons: decision authority belongs with approval-service transitions.
- Rejected.

### Separate reject limits higher than approve limits

- Pros: operational flexibility.
- Cons: weakens segregation story; one ceiling per role is simpler to explain in a demo.
- Rejected for v3; approve and reject share one limit.

## Consequences

- Positive: demo can show 403, `ESCALATED`, and supervisor resolution with a clear audit line.
- Risks: until 6A, legacy AGENT behavior contradicts this ADR; slice 0 makes that visible.
- Maintenance: limit config changes need versioning and tests (slice 6C).
- Scaling: limit checks are O(1) per decision; no hot-path remote calls.

## Implementation Plan

- Folder impact: `approval-service` decision endpoints, limit configuration, audit rows, claim read model updates via events for `ESCALATED`.
- Deployment: config or DB-backed limits per role; feature flag not required once 6A ships.
- Testing: test matrix row "Adjuster decides above their authority limit" (approve **and** reject over limit → 403 and `ESCALATED`); own-claim and supervisor-only escalation cases in slice 6.
- Rollback: disable limit enforcement via config only if a critical bug appears; do not remove audit rows.

## Teaching Section

### Architecture in simple terms

The adjuster has a spending ceiling on decisions they can make on someone else's claim. If the loss is bigger than that ceiling, the system says no and sends the file upstairs. The ceiling applies whether they wanted to pay or deny.

### Business value

Authority limits are how carriers delegate without giving every adjuster unlimited bind. Pairing them with escalation shows operational control.

### Long-term impact

Limit versions tie to rule versions so auditors can replay "what ceiling applied that day."

### Best practices

Check SoD before limits. Never clip amounts. Store actor, limit, and version on every decision.

### Future optimization

Per-line-of-business limits or geographic bands can extend the same comparison hook without moving enforcement out of approval-service.
