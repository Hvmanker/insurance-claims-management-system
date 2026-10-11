# ADR-003: Spring sessions, with Redis storage and an OIDC seam

## Status

Accepted as a plan decision. The product changes are not built yet.

## Date

2026-10-11

## Context

Claim-service already uses Spring Security, BCrypt strength 12, HttpOnly cookies, and SameSite strict. The filter chain currently permits every request and disables CSRF. Sessions are local to the JVM. Roles are INSURED and AGENT.

Business problem: staff and customers need a login that works across a gateway and more than one service instance, without a new identity vendor.

Technical constraints: same-origin behind Caddy. CSRF on writes. No tokens in local storage.

Scalability: local sessions pin a user to one JVM. Spring Session in Redis removes that pin.

Security: Cognito is the company guideline. It is a documented gap (EX-007). SSO, MFA, and multi-tenant orgs are non-goals.

Cost: no Cognito charges.

## Decision

Keep Spring sessions and BCrypt. Store sessions in Redis from slice 1 so services stay stateless. Enforce CSRF on POST, PUT, and DELETE. Leave a configuration seam for OIDC. Do not adopt Cognito in v1.

## Alternatives Considered

### Amazon Cognito now

- Pros: matches the guideline row.
- Cons: needs an AWS account and replaces working auth.
- Rejected: exception EX-007. The seam stays for later.

### JWT in local storage

- Pros: easy for a static frontend.
- Cons: the plan forbids tokens in local storage, and a static export is no longer the UI assumption.
- Rejected: cookies stay server-side.

## Consequences

- Positive: restarts do not require sticky sessions. Role claims travel in the session.
- Risks: if Redis is down, sign-in fails closed with a clear unavailable message. Claim data is safe.
- Maintenance: session values hold a user id and roles, not claim content.
- Scaling: more gateway or service instances share the session store.

## Implementation Plan

- Folder impact: claim-service security configuration, Redis, gateway.
- Deployment: Redis is already in Compose. Slice 5 adds a password, ACLs, and persistence for the state role.
- Testing: another user's claim is denied. Redis restart forces sign-in again and loses no claims.
- Rollback: slice 1 can keep a single-node session store. Do not return to CSRF disabled.

## Teaching Section

### Architecture in simple terms

The browser holds a cookie. Redis remembers who that cookie is. PostgreSQL remembers the claims.

### Business value

A demo login that matches how the app is actually protected, including the CSRF fix the current code still needs.

### Long-term impact

OIDC can replace the password check later without rewriting every permission check.

### Best practices

Rotate session ids on sign-in. Permission checks live on the server. The UI hides buttons the API would reject.

### Future optimization

Cognito or another OIDC provider behind the seam, after EA approval. MFA and SSO stay future work.
