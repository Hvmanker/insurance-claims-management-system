# EA exception: web hosting

Guideline row: Web hosting
Status: Gap
Matrix: deferred until slice 5.
Date: 2026-10-11

## What the guideline asks for

AWS Amplify.

## What v3 does

A Next.js server container (App Router, server components) sits behind Caddy. The browser does not receive a static-only export. Amplify Hosting can run SSR, and that remains the AWS target. It is not provisioned.

## Justification

Amplify is an AWS product. The demo needs one Node container so server-rendered pages and session-cookie forwarding work on Compose.

## Cost

About 0.3 GB for the Next.js server, to be measured under load. No Amplify hosting charges.

## Alternative

Amplify Hosting for the AWS target. The UI requirements stay binding if the host changes. Tailwind and shadcn/ui are a default, not a reason to require Amplify.

## Duration

For the free demo. A labelled frontend-only preview is allowed in slice 8 if the VM cannot run the server, and that preview must not imply the backend is live.
