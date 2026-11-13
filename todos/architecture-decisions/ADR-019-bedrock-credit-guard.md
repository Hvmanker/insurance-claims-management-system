# ADR-019: Check credits around each Bedrock Claude call

## Status

Accepted. Replaces the five-minute Lambda guard.

## Context

This project has a Free plan credit balance and can call Bedrock Claude. A Lambda running every five minutes reads CloudWatch metrics even when nobody is calling Claude. That CloudWatch read is billed from the first request, about $0.17 per month for one model. The sign-in role cannot be changed by this project, so a permanent IAM ban is not available.

## Decision

Delete the scheduled guard. Check credits only when a Claude call is about to happen.

- Run `ops/credit-guard/claude_guard.py`.
- Read the Free Tier balance before the call. If 90% of the highest balance seen is already used, do not call Claude, and remove the Anthropic model agreements.
- Make one Claude call.
- Read the balance again and add the call's token counts.
- Tokens per credit are total Claude tokens divided by credits consumed. The balance often does not move on a tiny call, so that figure stays empty until AWS posts the spend.
- Remember the highest balance in `ops/credit-guard/state.json` on this machine. That file is local and is not a cloud resource.

## Alternatives Considered

- Five-minute Lambda: rejected because it spends credit while idle.
- IAM deny on the sign-in role: rejected because the project service control policy blocks changes to `AccountFullAccessRole`.
- Daily budget email: rejected because it can arrive a day late.

## Consequences

- Idle time costs nothing from this guard.
- Playground and unguarded CLI calls are not checked until the next guarded call. At that point, if the balance is already at 90%, the agreements are removed.
- A call can recreate an agreement after removal. The following guarded call removes it again.
- The baseline lives on this computer. Another computer starts from the balance it sees.

## Implementation Plan

- Removed: Lambda `credit-guard`, its schedule, topic, role, and parameter store values.
- Added: `ops/credit-guard/claude_guard.py` and `.cursor/rules/bedrock-credit-guard.mdc`.
- Rollback: stop using the script. Recreate model agreements if they were removed.

## Teaching Section

A timer that asks AWS "how much did we spend?" costs money even when Claude is idle. Checking immediately before and after a real call measures the same thing only when there is something to measure. The before-check refuses a call that would cross the line. The after-check records the tokens and the new balance.

Best practice: put the meter on the action, not on a clock, when the clock itself is a billed API. A role deny would stop the playground too, and this project is not allowed to add one.
