# EA exception: identity

Guideline row: Identity
Status: Gap
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

Amazon Cognito.

## What v3 does

Spring Security sessions, BCrypt, and role claims. From slice 1, sessions are stored in Redis so services stay stateless. CSRF is required on writes. A configuration seam is reserved for OIDC later. SSO, MFA, and multi-tenant organizations are explicit non-goals.

## Justification

The repo already authenticates portal users. Replacing that with Cognito would spend the demo on an identity migration and would require an AWS account. The product value is the claims workflow and the eight-role permission model.

## Cost

No Cognito monthly or MAU charges. Redis session storage is inside the 128 to 256 MB maxmemory budget.

## Alternative

Cognito, or another OIDC provider, behind the config seam after EA approval. Role names in the session should map cleanly so approval rules do not fork.

## Duration

For v1 of this demo. Enterprise identity is future work, not a slice 6 feature.
