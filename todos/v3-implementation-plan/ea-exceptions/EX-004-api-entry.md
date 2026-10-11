# EA exception: API entry

Guideline row: API entry point
Status: Partly. The behavior is an API front door. The product is not API Gateway.
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

Amazon API Gateway.

## What v3 does

Caddy is the only public listener. It terminates HTTPS and forwards API calls to Spring Cloud Gateway on the private network. The gateway performs request filtering and rate limits. Services are not published on the host except during local development before Caddy exists.

## Justification

API Gateway needs an AWS account. Spring Cloud Gateway gives route configuration, filters, and rate limits in the same Java stack as the services, which keeps the demo runnable with Compose.

## Cost

One extra JVM on the VM, budgeted at about 0.5 GB heap. No API Gateway request charges.

## Alternative

API Gateway in front of ECS on the AWS target. Keep route and auth rules in configuration so the move does not rewrite the services.

## Duration

For the free demo. Revisit with the AWS target, not as a slice 1 blocker.
