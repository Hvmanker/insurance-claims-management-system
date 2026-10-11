# ADR-010: Next.js App Router as the default UI

## Status

Accepted as a working assumption. The UI requirements are the binding decision. The framework may change if those requirements still hold. The product is not built yet.

## Date

2026-10-11

## Context

An earlier draft used a static Next.js export and polling. That avoided a Node server and also avoided showing server rendering. The updated plan wants dynamic, role-specific workspaces, URL state, and live updates.

Business problem: eight roles need different screens over one API, with a 403 that explains itself and a claim page that survives refresh.

Technical constraints: one more container, about 0.3 GB. Session cookie forwarded to the backend. SSE for live status, polling as fallback. Tailwind and shadcn/ui are the default component approach.

Scalability: live fan-out moves to Redis pub/sub in slice 7. Each UI instance pushes to its own clients.

Security: no tokens in local storage. Personal data stays out of URLs and client logs. CSRF stays on writes.

Cost: one Node container. Amplify is the AWS target and is a gap (EX-006).

## Decision

Assume current stable Next.js with the App Router and server components, running as a Node container behind Caddy. Treat Tailwind and shadcn/ui as a default. If a different UI stack is chosen, it still has to meet the role screens, URL state, live updates, accessibility checks, and the slice gates.

## Alternatives Considered

### Static export and polling only

- Pros: no Node server, cheaper RAM.
- Cons: weaker fit for role-aware server rendering, and it is the withdrawn draft.
- Rejected for this plan.

### Keep the claim-service static portal

- Pros: already served on port 8081.
- Cons: it cannot host eight workspaces cleanly, and it couples UI deploys to the claim JVM.
- Rejected: slice 1 introduces the app shell.

## Consequences

- Positive: the strongest Next.js signal in the standards matrix is actually demonstrated.
- Risks: RAM. Measure in slice 1.
- Maintenance: UI requirements live in the narrative docs. A framework swap edits this ADR rather than the slice list.
- Scaling: staff screens are desktop-first. Insured and agent screens must work on a phone.

## Implementation Plan

- Folder impact: a new frontend app, Caddy route, slice 1 shell, slice 7 workspaces.
- Deployment: Node container on the private network, Caddy in front.
- Testing: Playwright journeys and axe in slice 7. Slice 1 has one browser end-to-end test.
- Rollback: the API gates do not depend on the framework. A failed UI slice does not roll back the rule engine.

## Teaching Section

### Architecture in simple terms

The browser loads pages from Next.js. Next.js calls the API with the user's cookie. The API, not the page, decides what they may do.

### Business value

Reviewers can sign in as different people and see different work. That is the product, not the choice of CSS kit.

### Long-term impact

Amplify can host the same server-rendered app later. The screens do not get rewritten for that move.

### Best practices

Forbidden routes render an explanation and return no extra data. Decisions wait for the server. Settlements are labelled simulated.

### Future optimization

Grafana-free local dev can still run the UI. Live updates degrade to polling when Redis pub/sub is down.
