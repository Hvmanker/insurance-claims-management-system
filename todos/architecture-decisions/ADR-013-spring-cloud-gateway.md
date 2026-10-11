# ADR-013: Spring Cloud Gateway behind Caddy

## Status

Accepted as a working assumption. The product is not built yet.

## Date

2026-10-11

## Context

The guideline asks for API Gateway. The demo cannot create AWS API Gateway resources. The browser still needs one HTTPS origin, security headers, and a place for rate limits that is not scattered through four services.

Business problem: one front door, so cookies are same-origin and internal ports stay private.

Technical constraints: Caddy is public. It forwards pages to Next.js and API calls to Spring Cloud Gateway. The gateway filters requests and enforces rate limits. Services sit on the private Compose network.

Scalability: the gateway is another JVM, about 0.5 GB heap. Measure it in slice 1.

Security: headers at Caddy. Rate limits fail open with a small local limit and an alert if Redis is down (slice 5). CSRF remains at the application.

Cost: no API Gateway charges. RAM is the cost. EX-004 records the product gap.

## Decision

Use Spring Cloud Gateway as the API entry, behind Caddy. Caddy's job is HTTPS and the public socket. Do not expose Postgres, Kafka, Redis, the object store, or the worker.

## Alternatives Considered

### Caddy routing directly to each service

- Pros: one fewer JVM.
- Cons: rate limits and request filtering would live in Caddy config or be copied into every service. The plan assigns them to the gateway.
- Rejected.

### Amazon API Gateway

- Pros: the guideline product.
- Cons: AWS account. EX-004.
- Rejected for the demo.

## Consequences

- Positive: one origin, one place for route policy, services stay private.
- Risks: memory pressure. If slice 1 shows the host cannot fit the gateway, lower heaps before merging services.
- Maintenance: route config is versioned with the app.
- Scaling: more than one gateway instance can share Redis-backed sessions and rate-limit buckets.

## Implementation Plan

- Folder impact: new gateway module, Caddyfile, Compose.
- Deployment: slice 1 can introduce the gateway with the app shell. Slice 5 adds Redis rate limits. Slice 8 adds the public TLS listener.
- Testing: unauthorized routes, rate-limit fail-open once slice 5 exists, and a check that only Caddy is published on a VM.
- Rollback: local dev may still call a service port. The demo path uses the gateway.

## Teaching Section

### Architecture in simple terms

Caddy is the door lock on the building. The gateway is the reception desk that knows which office handles which request.

### Business value

The API entry row is "partly" for an honest reason: the behavior is there, the AWS product is not.

### Long-term impact

Routes can move to API Gateway later if the services keep stable HTTP contracts.

### Best practices

Private network by default. Upload size limits at both Caddy and the service. Do not trust the gateway as the only authorization check.

### Future optimization

API Gateway on ECS after EA approval. Keep permission checks in the services either way.
