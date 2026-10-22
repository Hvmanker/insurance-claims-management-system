# Tester spec: 1A.3 (approval-service single writer, first decision wins, 409 + CONFLICT audit, internal lookup endpoint) — v7
Base: `cursor/1a-2b-kafka-topics` final tip. At 21:11 IST it was still `2fe08e44d263866e0b4307c1986b8e7d7f26e311`; Coder branches from whatever the final tip is.
Sources:
- `/workspace/phase1a-subtasks.md` **rev 10** (1A.3 section and Accept, Execution order, the Tester criteria table T2-T5, D1, D4, D5). The 1A.3 text is unchanged from rev 9. Rev 10 adds the parallel order: agent A runs 1A.2b, then 1A.3, then 1A.4; agents B (1A.6) and C (1A.5) code against 1A.3's specced contracts and are graded only after 1A.3 lands.
- `/workspace/architect-1A.3-answers.md`, sha256 `a04fadbe7d3e50b280bb987caac1e9f65d5b1ff5cfb7ee58d953e19b4c551271`, items 1-10 plus C2 and C3. v1 of this spec was sha `6589fccb…7e0f`, and v2 was `aac6d99c…f49a`. v3 was `fbc9245b…303f` (kept as `tester-spec-1A.3.v3.bak`). v4 was `8b4efee2…59ae` (kept as `tester-spec-1A.3.v4.bak`). **v7** (Lead via Simplifier, 21:57 IST) adds 3 cases to `invalidDecisionBodyIs400` (malformedJson, actionUnknown, decidedByMissingNoWorkflow), so the bar moves 164 → **167** (approval-service failsafe 33 → 36); it also re-points the deferred race seam at 1A.4's `@SpyBean` spec. v6 was `37da4232…9939` (kept as `tester-spec-1A.3.v6.bak`). v5 only marks OQ-A, OQ-B and OQ-C resolved (Lead, 21:24 IST); tests and counts are unchanged at 164. v4 applied Lead's three decisions from 21:21 IST (removal ownership, the interim adapter backoff, the lookup DTO). It also adds a 6-case 400 validation test, an adapter backoff and drop-log test, and a blank-token startup test.

All data is synthetic: fixed UUIDs below, the test token `test-internal-token-1A3`, no real keys, no real payment data.

## 0. Requirements for Coder (checked in the repo at 2fe08e4)
1. **Pickup (Lead rule: no test may be silently skipped).** At 2fe08e4 no POM declares `maven-failsafe-plugin`. Surefire 3.2.5 uses its default includes, which do not match `*IT`.
   - **Choice: add failsafe, keep the name `ApprovalDecisionIT`** (the plan's name; rev 9 also lists the POM change).
   - Add `maven-failsafe-plugin` **3.2.5** to root `pluginManagement`, and declare it in `approval-service/pom.xml` with the `integration-test` and `verify` goals.
   - The unit classes (`ApprovalServiceTest`, `ClaimSubmittedListenerTest`, `InternalApiTokenStartupTest`) are `*Test`, so surefire runs them.
2. **Test dependencies.** approval-service only has `spring-boot-starter-test` today. Add these at test scope:
   - `org.testcontainers:postgresql`, `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter` (root BOM 1.21.4)
   - `org.awaitility:awaitility`
   - Use the same container classes as `common/.../OutboxInboxTestSupport.java`: `PostgreSQLContainer` with `postgres:16-alpine`, and `org.testcontainers.kafka.KafkaContainer` with `apache/kafka:3.8.0`.
3. **Security is new code** (answer 1). There is no security dependency or config in approval-service today. The token check:
   - checks `Authorization: Bearer <INTERNAL_API_TOKEN>` on `/internal/**`;
   - returns 401 when there is no credential, including when only a session cookie is sent;
   - returns 403 for a bearer token that isn't the service token;
   - compares with `MessageDigest.isEqual` and never logs the token;
   - fails at startup if the token is blank;
   - leaves `/actuator/health` open.

## Container and harness rules (every test marked [TC])
- **Real containers only.** No H2, no embedded broker, no mocked JDBC.
- **Docker missing means ERROR, never skip.** No `disabledWithoutDocker`, no `assume*`, no `@Disabled`.
- **No sleeps, timing windows, retries of a test or a request, or "at most"/"at least" counts.** Every count below is exact.
- **Async waits.** Tests 1 and 18 consume from Kafka, so they wait with Awaitility (bounded 30 s) for a committed DB fact. A timeout is a FAIL.
  - Test 1 waits for the `processed_events` row.
  - Test 18 waits for the `claim_approvals` row count to equal 1, then for the `decision_audit` count to equal 1.
  - All counts are asserted only after that, and they are exact.
- **Everything else is synchronous HTTP** with `java.net.http.HttpClient` (it supports PATCH, and a POST that gets a 401 doesn't throw), against `@SpringBootTest(webEnvironment = RANDOM_PORT)`.
- `@DynamicPropertySource` sets the DB properties, `KAFKA_BOOTSTRAP_SERVERS` and `INTERNAL_API_TOKEN=test-internal-token-1A3`.
- `@BeforeAll` creates the topics `claim.submitted`, `claim.manual-decision`, `claim.approved`, `claim.rejected` and `claim.analysis.requested` with AdminClient (1 partition each). The test does not rely on broker auto-create.
- `@BeforeEach` truncates these tables in schema `approvals`: `claim_workflow`, `claim_approvals`, `decision_audit`, `outbox`, `processed_events`.
- **Decision outbox rows** for claim X = `count(*) FROM approvals.outbox WHERE aggregate_id=X AND topic IN ('claim.approved','claim.rejected')`.
- **Valid service token** = header `Authorization: Bearer test-internal-token-1A3`.

## Fixed synthetic IDs
`...` = `00000000-0000-0000-0000-`

| Name | Value |
|---|---|
| Agent A / Agent B | `...0000000003a1` / `...0000000003a2` |
| Auto actor | `system:auto-approval`, role `SYSTEM` (`DecidedBy.AUTO_APPROVAL`) |
| Matrix claims M1..M4 | `...000000000301`..`...000000000304` |
| Human-loses claims H1 / H2 | `...000000000311` / `...000000000312` |
| T3a claim, eventId E1 | `...000000000321`, `...0000000003e1` |
| T5 claims (approve / reject) | `...000000000322` / `...000000000323` |
| Partial-approval claim | `...000000000324` |
| Manual-decision adapter claim | `...000000000325` |
| Over-claim 400 claim / reject-with-amount 400 claim | `...000000000326` / `...000000000327` |
| Claim with no workflow row (item 9) | `...000000000328` |
| 400 validation claims V1..V6 | `...000000000341`..`...000000000346` |
| 400 validation claims V7..V9 (v7) | `...000000000348`, `...000000000349`, `...000000000350` (V9 is **never seeded**) |
| Dropped manual-decision claim (drop log) | `...000000000347` |
| Case 3 claim, eventIds E1 / E2 | `...000000000331`, `...0000000003e1` / `...0000000003e2` |
| C2 claim (no eventId header) | `...000000000332`. Derived key = `UUID.nameUUIDFromBytes("claim.submitted:00000000-0000-0000-0000-000000000332".getBytes(UTF_8))` = **`a5e2f3d3-e906-3bcf-b263-9d41ba92fe03`** |
| Lookup claims (approved / rejected) | `...000000000335` / `...000000000336` |
| Seeded auto decisionIds (H1 / H2) | `...0000000003d1` / `...0000000003d2` |
| Seeded auto outbox eventIds (H1 / H2) | `...0000000003c1` / `...0000000003c2` |
| Unknown decisionId | `...0000000003ff` |
| Manual-decision actor string | `agent-1a3@test.local` (see note N1) |
| Race claims (1A.4 only) | human-wins 350+r, auto-wins 370+r |

Fixed test data:
- Policy number `POL-1A3-TEST`.
- Claimed amount `4000.00`, which is inside the 10000.00 auto limit.
- Reason `test reason 1A.3`.

**Seeding a decidable claim.** Insert directly with JDBC into `approvals.claim_workflow(claim_id, status, policy_number, claimed_amount, updated_at)`, with status `ANALYSIS_PENDING` and amount 4000.00.

**Decide request** (answer 2): `POST /internal/claims/{claimId}/decisions` with a valid token. The body is `{"action":"APPROVED"|"REJECTED","reason":"test reason 1A.3","decidedBy":{"userId":"<agent uuid>","role":"AGENT"}}`.
- `approvedAmount` is **absent** unless a test says otherwise.
- When `approvedAmount` is absent on an APPROVED decision, it defaults to `claim_workflow.claimed_amount` (answer 5).

**Code bodies.** "The 409 body", "the 400 body" and "the 404 body" mean the status shown, with a JSON body whose key set is exactly `{code}`:

| Status | `code` |
|---|---|
| 409 | `CLAIM_ALREADY_DECIDED` |
| 400 | `INVALID_DECISION_REQUEST` |
| 404 (decide) | `CLAIM_NOT_FOUND` |

**"Writes nothing" for claim X** means all of these:
- `claim_approvals` = 0
- outbox rows for aggregate X = 0
- `decision_audit` = 0
- `claim_workflow.status` is unchanged, or there is still no row.

**The CONFLICT row check** (U, R, D) for claim X:
- `count(*) FROM approvals.decision_audit WHERE claim_id = X` = **1**. Answer 4: a winning decision writes no audit row, so a clean claim has 0 and a conflicted claim has exactly 1.
- That row has `outcome='CONFLICT'`, `reason_code='CLAIM_ALREADY_DECIDED'`, `actor_user_id=U`, `actor_role=R`, `attempted_decision=D` (`APPROVED` or `REJECTED`, answer 3), and `at` is not null.

## approval-service: ApprovalDecisionIT [TC, Postgres + Kafka] (21 methods, 36 executions, failsafe)

1. **`submittedClaimIsAnalysisPendingAndNotApproved`** (T3a)
   - Input: produce to `claim.submitted` with key = claim 321 and a single header `eventId` = E1 (no `__TypeId__`). The value is the JSON of `ClaimSubmittedEvent(321, "POL-1A3-TEST", "Test Member", "AUTO", 4000.00, 2026-07-01T12:00:00Z)`.
   - Wait until `processed_events` has (`approval-service`, E1).
   - Expect:
     - `claim_workflow` has exactly 1 row, with status `ANALYSIS_PENDING`, policy `POL-1A3-TEST` and amount `compareTo(4000.00)==0`.
     - `claim_approvals` = 0.
     - Outbox: exactly 1 row for the aggregate, with topic `claim.analysis.requested`.
     - Decision outbox rows = 0.
     - `decision_audit` = 0.

2. **`humanLosesToSeededAutoApproval(action)`** — `@ParameterizedTest @ValueSource(strings={"APPROVED","REJECTED"})`, 2 executions: H1 is APPROVED, H2 is REJECTED (D1).
   - Seed in one transaction:
     - `claim_workflow` with status `APPROVED`;
     - a `claim_approvals` row: decision `APPROVED`, decision_id `3d1`/`3d2`, source `AUTO`, decided_by `system:auto-approval`/`SYSTEM`, amounts 4000.00, reason `test reason 1A.3`, decided_at `2026-07-01T12:05:00Z`;
     - an outbox row with event_id `3c1`/`3c2` and topic `claim.approved`.
   - Snapshot the `claim_approvals` row.
   - Act: Agent A decides `action`.
   - Expect:
     - the 409 body;
     - `claim_approvals` has exactly 1 row, equal to the snapshot column for column;
     - status is still `APPROVED`;
     - decision outbox rows = 1, and it is the seeded event_id;
     - the CONFLICT row check with (Agent A, `AGENT`, `action`).

3. **`secondDecideMatrix(first, second)`** — `@ParameterizedTest @CsvSource`, 4 executions: `APPROVED,APPROVED` (M1), `APPROVED,REJECTED` (M2), `REJECTED,APPROVED` (M3), `REJECTED,REJECTED` (M4).
   - Agent A decides `first` and gets **200**. Snapshot the row.
   - Agent B decides `second`.
   - Expect:
     - the 409 body (never 200, never 403);
     - exactly 1 `claim_approvals` row, equal to the snapshot: decision=`first`, Agent A, `AGENT`, source `HUMAN`, decision_id unchanged;
     - status = `first`;
     - decision outbox rows = 1, with the topic matching `first`;
     - the CONFLICT row check with (Agent B, `AGENT`, `second`).

4. **`approveOutboxPayloadCarriesDecidedByAgent`** (T5) — Agent A approves claim 322, with no `approvedAmount`.
   - Expect 200.
   - Exactly 1 decision outbox row, with topic `claim.approved`. Read its payload with Jackson `USE_BIG_DECIMAL_FOR_FLOATS`. It must have:
     - a `decidedBy` key set exactly `{userId, role}`, with userId `"00000000-0000-0000-0000-0000000003a1"` and role `"AGENT"`;
     - `decisionId` = `claim_approvals.decision_id`;
     - `eventId` = `outbox.event_id`;
     - `claimId` = 322;
     - `approvedAmount` `compareTo(4000.00)==0` (the default);
     - `claimedAmount` `compareTo(4000.00)==0`.
   - The row has source `HUMAN`, Agent A / `AGENT`, and `approved_amount` 4000.00.
   - `decision_audit` = 0.

5. **`rejectOutboxPayloadCarriesDecidedByAgent`** (T5) — Agent A rejects claim 323.
   - Expect 200.
   - Exactly 1 decision outbox row, with topic `claim.rejected`. Its payload has:
     - a `decidedBy` key set exactly `{userId, role}`: Agent A, `"AGENT"`;
     - `decisionId` = the row's decision_id;
     - `eventId` = `outbox.event_id`;
     - `claimId` = 323.
   - `decision_audit` = 0.

6. **`decideWithoutServiceTokenIs401`** — a valid APPROVED body for a seeded claim, sent with no `Authorization` header.
   - Expect 401, and writes nothing.

**Lookup setup for tests 7-13:** Agent A approves claim 335 (no amount, so 4000.00) and rejects claim 336. Both return 200. Read their decision_ids, dA and dR.

7. **`lookupWithoutServiceTokenIs401`** — GET `/internal/decisions/{dA}` with no headers.
   - Expect 401, and the body does not contain claim 335's id.
8. **`lookupWithUserSessionCookieIs401`** — only `Cookie: JSESSIONID=test-user-session-1A3`.
   - Expect 401.
9. **`lookupWithUserTokenIs403`** — `Authorization: Bearer test-user-token-1A3`.
   - Expect 403.
10. **`lookupNonGetMethodsAre405(method)`** — `@ValueSource(strings={"POST","PUT","DELETE","PATCH"})`, 4 executions. Each sends a valid token and the body `{}` to `/internal/decisions/{dA}`.
    - Expect 405.
    - The 335 row is unchanged.
    - HEAD and OPTIONS are excluded (answer 8).
11. **`lookupApprovedReturnsExactKeySet`** — GET dA with a valid token.
    - Expect 200.
    - The top-level key set **equals** `{decisionId, claimId, action, approvedAmount, decidedBy}`. This is set equality, not containsAll.
    - The `decidedBy` key set equals `{userId, role}`.
    - Values: dA, claim 335, `"APPROVED"`, approvedAmount `compareTo(4000.00)==0`, and decidedBy `{"userId":"00000000-0000-0000-0000-0000000003a1","role":"AGENT"}`.
    - The raw body contains neither `POL-1A3-TEST` nor `test reason 1A.3`.
12. **`lookupRejectedReturnsExactKeySet`** — GET dR.
    - Expect 200 and the same exact key set.
    - action is `"REJECTED"`.
    - `approvedAmount` is **present and JSON null** (answer 6).
    - The `decidedBy` key set is `{userId, role}`.
    - The body has no policy number and no reason.
13. **`lookupUnknownIdIs404`** — GET `...0000000003ff` with a valid token.
    - Expect 404.
14. **`actuatorHealthOpenWithoutToken`** — `GET /actuator/health` with no headers.
    - Expect 200.
15. **`partialApprovalAmountShowsOnLookupAndPayload`** (answer 5) — Agent A approves claim 324 with `"approvedAmount":2500.00`.
    - Expect 200.
    - `claim_approvals.approved_amount` `compareTo(2500.00)==0`.
    - Exactly 1 `claim.approved` outbox row, whose payload has `approvedAmount` 2500.00 and `claimedAmount` 4000.00.
    - GET `/internal/decisions/{decision_id}` returns the exact key set from test 11, with `approvedAmount` `compareTo(2500.00)==0`.
    - `decision_audit` = 0.
16. **`approveAboveClaimedAmountIs400`** — Agent A approves claim 326 with `"approvedAmount":4000.01`.
    - Expect the 400 body.
    - Writes nothing; the status is still `ANALYSIS_PENDING`.
17. **`rejectWithApprovedAmountIs400`** — Agent A rejects claim 327 with `"approvedAmount":100.00`.
    - Expect the 400 body.
    - Writes nothing; the status is still `ANALYSIS_PENDING`.
18. **`manualDecisionEventAdapterDecidesOnceThenConflicts`** (C3 and the rev 9 "Manual-decision listener" Accept)
    - Seed claim 325 as decidable.
    - Produce message 1 to `claim.manual-decision`, serialized the way claim-service's `ClaimEventPublisher` does it today (`JsonSerializer`, type headers on). The value is `ManualClaimDecisionEvent(325, "POL-1A3-TEST", 4000.00, approved=true, "test reason 1A.3", "agent-1a3@test.local", 2026-07-01T12:10:00Z)`.
    - Wait until `claim_approvals` count for 325 = 1. Then expect:
      - exactly 1 `claim_approvals` row: decision `APPROVED`, source `HUMAN`, `decided_by_user_id='agent-1a3@test.local'`, `decided_by_role='AGENT'`, `approved_amount` 4000.00 (the adapter passes `approvedAmount=null`, so the default applies), reason `test reason 1A.3`;
      - decision outbox rows = 1, with topic `claim.approved` and payload `decidedBy` = `{"userId":"agent-1a3@test.local","role":"AGENT"}`;
      - `decision_audit` = 0;
      - status `APPROVED`;
      - `processed_events` = 0 (no inbox step, per C3(c)).
    - Then produce message 2, the same but with `approved=false`.
    - Wait until `decision_audit` count for 325 = 1. Then expect:
      - still exactly 1 `claim_approvals` row, equal to the earlier one;
      - decision outbox rows still 1;
      - the CONFLICT row check with (`agent-1a3@test.local`, `AGENT`, `REJECTED`).
19. **`decideOnClaimWithoutWorkflowRowIs404`** (answer 9) — Agent A approves claim 328, which has no `claim_workflow` row.
    - Expect the 404 body with `CLAIM_NOT_FOUND`.
    - Writes nothing: `claim_approvals` = 0, `decision_audit` = 0, outbox = 0, and still no `claim_workflow` row.
20. **`invalidDecisionBodyIs400(caseName, claimId, seedWorkflow, bodyJson)`** (answers 2 and 5) — `@ParameterizedTest(name = "{0}") @MethodSource`, **9 executions, IT (failsafe)**. It is an IT because "writes nothing" is asserted against the real DB and the real HTTP validation path.
    - Cases V1..V8 seed their own decidable claim (`ANALYSIS_PENDING`, claimed 4000.00, `seedWorkflow=true`). V9 seeds nothing (`seedWorkflow=false`).
    - Each case POSTs with a valid token, as Agent A, using the standard body with exactly one field changed:

    | Case name | Claim | Change from the standard APPROVED body |
    |---|---|---|
    | `amountZero` | V1 `...341` | `"approvedAmount":0.00` |
    | `amountNegative` | V2 `...342` | `"approvedAmount":-1.00` |
    | `amountScaleThree` | V3 `...343` | `"approvedAmount":100.001` |
    | `roleNotAgent` | V4 `...344` | `"decidedBy":{"userId":"<Agent A>","role":"ADJUSTER"}` |
    | `reasonBlank` | V5 `...345` | `"reason":"   "` |
    | `reasonTooLong` | V6 `...346` | `"reason"` = `"r"` repeated **501** times |
    | `malformedJson` (v7) | V7 `...348` | the body is the literal text `{"action":"APPROVED",` (truncated, not valid JSON) |
    | `actionUnknown` (v7) | V8 `...349` | `"action":"MAYBE"` |
    | `decidedByMissingNoWorkflow` (v7) | V9 `...350`, **no `claim_workflow` row** | the `decidedBy` member is omitted entirely |

    - Zero and negative are two cases, not one. "> 0" has two distinct failure modes: a `>= 0` bug passes 0.00, and a missing sign check passes -1.00. One case can't catch both.
    - The 500-character boundary itself (500 is valid) is already covered by every other test's valid reason path, so it is not repeated here.
    - Expect for every case:
      - the 400 body `{"code":"INVALID_DECISION_REQUEST"}` (key set exactly `{code}`);
      - writes nothing for that claim (`claim_approvals` = 0, `decision_audit` = 0, outbox = 0);
      - V1..V8: status still `ANALYSIS_PENDING`. V9: still **no** `claim_workflow` row (count = 0).
    - **Coder contract (v7): validation runs before the workflow lookup.** Every structural check runs before `claimWorkflowRepository.findById`:
      - the body parses;
      - `action` is `APPROVED` or `REJECTED`;
      - `decidedBy` is present, its `role` is `AGENT` and its `userId` is non-blank;
      - `reason` is non-blank and at most 500 characters;
      - `approvedAmount` is > 0, has scale ≤ 2, and is null on REJECTED.

      Only `approvedAmount ≤ claimed_amount` needs the row, so only that check runs after the lookup. A *valid* body on a missing claim is still 404 (test 19).
    - **Expected FAIL at 0654e8e: `decidedByMissingNoWorkflow` returns 500.** `ApprovalService.recordHumanDecision` calls `InternalDecisionValidator.validate` only `if (workflow != null)`, then dereferences `request.decidedBy().userId()`. That throws an NPE, and nothing maps it to 400.
    - `malformedJson` (`HttpMessageNotReadableException` handler, added in 0654e8e) and `actionUnknown` (`parseAction`) already map to 400 at 0654e8e, judging from source.
21. **`manualDecisionErrorHandlerUsesFixedBackOffAndLogsDrop`** (Lead decision 2; deterministic, no Kafka delivery, no timing). It reads beans from the running test context.
    - **Coder contract (required so this test can read the values):**
      - Expose a bean named **`manualDecisionBackOff`** of type `org.springframework.util.backoff.FixedBackOff`.
      - Expose a bean named **`manualDecisionErrorHandler`** of type `DefaultErrorHandler`, built from that backoff and a recoverer.
      - The `claim.manual-decision` listener's container factory uses that handler. I check the wiring in review.
    - **Assert on the backoff:**
      - `getInterval() == 2000L`
      - `getMaxAttempts() == 14L`
      - This is the interpretation below: 15 deliveries in total = 1 initial + 14 retries, about 28 s of backoff ("about 30 s"). Resolved, OQ-A.
    - **Assert on the recoverer** (call `manualDecisionErrorHandler`'s recoverer through the `ConsumerRecordRecoverer` bean the Coder exposes as **`manualDecisionRecoverer`**):
      - Pass it a `ConsumerRecord` on `claim.manual-decision` whose key and value are for claim 347, plus a test exception.
      - Exactly one ERROR event is captured (ListAppender), with formatted message **`manual decision dropped claimId=00000000-0000-0000-0000-000000000347 alert=true`**. Resolved, OQ-B: `alert=true` always comes last in the message text.
      - No rows are written for 347.

## approval-service: ApprovalServiceTest (unit, Mockito; surefire; net +2 methods / +2 executions)
The two existing methods are **replaced** (the inversion). Their old names must not appear in the reports.
- `autoApprovesClaimsWithinTheConfiguredLimit` becomes **`doesNotApproveOnSubmitWithinTheLimit`** (9000.00, first-time eventId). Expect:
  - `ClaimApprovalRepository.save` is never called;
  - `OutboxWriter.append` is called exactly once, with topic `claim.analysis.requested`;
  - no append for `claim.approved` or `claim.rejected`;
  - no `KafkaTemplate` interaction.
- `leavesHighValueClaimsForManualReview` becomes **`doesNotApproveOnSubmitAboveTheLimit`** (12500.00), with the same assertions.

The case 3 tests mock `InboxGuard`, the ClaimWorkflow insert-if-absent call (it returns 1 or 0) and `OutboxWriter`. A logback `ListAppender` captures the logger that emits the line. Both use claim 331.
- **`replayedSameEventIdAddsNoRowNoEventAndLogsDuplicate`**
  - First: E1 with `firstTime` true and insert 1.
  - Then: E1 again with `firstTime` false.
  - Expect:
    - the insert ran exactly once and `append` ran exactly once;
    - `save` was never called;
    - exactly one INFO event, equal to `duplicate event skipped claimId=00000000-0000-0000-0000-000000000331 eventId=00000000-0000-0000-0000-0000000003e1` (answer 7).
- **`newEventIdSameClaimAddsNoRowNoEventAndLogsDuplicate`**
  - First: E1 with `firstTime` true and insert 1.
  - Then: E2 with `firstTime` true and insert 0.
  - Expect:
    - `append` ran exactly once in total;
    - `save` was never called;
    - exactly one INFO event, equal to `duplicate claim.submitted claimId=00000000-0000-0000-0000-000000000331 eventId=00000000-0000-0000-0000-0000000003e2`;
    - no `duplicate event skipped` line.

## approval-service: ClaimSubmittedListenerTest (unit, Mockito; surefire; new, 1 method / 1 execution)
- **`missingEventIdHeaderUsesDerivedKey`** (C2)
  - Build `ClaimSubmittedListener` over a real `ApprovalService` wired to mocks (InboxGuard returns true, insert-if-absent returns 1, OutboxWriter).
  - Call the `claim.submitted` handler for claim 332 with a **null / absent** `eventId` header.
  - Expect:
    - `InboxGuard.firstTime("approval-service", a5e2f3d3-e906-3bcf-b263-9d41ba92fe03)` was called exactly once. Assert the literal, and assert it equals `UUID.nameUUIDFromBytes(("claim.submitted:" + claimId).getBytes(UTF_8))`.
    - The workflow insert ran exactly once.
    - `append(claim.analysis.requested)` ran exactly once.
    - Exactly one WARN event, equal to `claim.submitted without eventId claimId=00000000-0000-0000-0000-000000000332`.

## approval-service: InternalApiTokenStartupTest (unit, no containers; surefire; new, 1 method / 1 execution)
- **`blankServiceTokenFailsStartup`** (answer 1: "Fail at startup if the token is blank")
  - Use `ApplicationContextRunner` with the configuration class that holds the token check. Coder names it, for example `InternalApiSecurityConfig`. No DB or Kafka, so a failure can only come from the token.
  - Run it twice in the one method, with `INTERNAL_API_TOKEN=""` and then `INTERNAL_API_TOKEN="   "`.
  - For each run, expect:
    - `context.hasFailed()` is true;
    - the failure's root cause is an `IllegalStateException` whose message contains `INTERNAL_API_TOKEN`;
    - the message does not contain the token value.
  - Control in the same method: `INTERNAL_API_TOKEN="test-internal-token-1A3"` gives `hasFailed()` false.
  - The answers define no exact exception or message, so Lead fixed the contract (OQ-C, resolved): an `IllegalStateException` whose message names `INTERNAL_API_TOKEN` and never contains the value.

## LANDS WITH 1A.4, not counted in 1A.3 totals: case 2 both-orders latch race (in ApprovalDecisionIT)
These need 1A.4's auto path, which calls `DecisionConflictRecorder` as `system:auto-approval` when its update changes 0 rows.

**Seam (superseded in v7 by `tester-spec-1A.4.md` §0.2).** The latches come from a Mockito `@SpyBean ClaimWorkflowStore`:
- `transitionDecidedStatus(UUID, String)` is the human path;
- `transitionFromAnalysisPending(UUID, "APPROVED")` is the auto path.

Use per-source `entered`/`release` CountDownLatch(1) pairs, with each await bounded at 10 s; hitting the bound is a FAIL. No test-only hook stays in production code.

**Each repetition:**
- Seed `ANALYSIS_PENDING` with 4000.00.
- Thread H: Agent A sends `POST .../decisions APPROVED`.
- Thread S: calls the `claim.analysis.completed` handler directly (no flags, fixed eventId).
- Await both `entered` latches.

**Methods:**
- **`caseTwoRaceHumanWins`** — `@RepeatedTest(20)`, claim 350+r.
  - Release H, join it (200, committed), then release S and join it.
  - Expect:
    - 1 `claim_approvals` row (`HUMAN`, Agent A);
    - 1 `claim.approved` outbox row;
    - the CONFLICT check with (`system:auto-approval`, `SYSTEM`, `APPROVED`);
    - status `APPROVED`.
- **`caseTwoRaceAutoWins`** — `@RepeatedTest(20)`, claim 370+r.
  - Release S and join it, then release H and join it.
  - Expect:
    - H gets the 409 body;
    - 1 `claim_approvals` row (`AUTO`, `system:auto-approval`);
    - 1 decision outbox row with decidedBy `system:auto-approval`;
    - the CONFLICT check with (Agent A, `AGENT`, `APPROVED`).

This adds 2 methods and 40 executions (failsafe) to **1A.4**.

## Static checks (my review, not tests)
- `git grep -n "kafkaTemplate.send" -- approval-service/src/main` is empty. Everything goes through `OutboxWriter`.
- Only `DecisionConflictRecorder` writes `decision_audit`.
- The `claim.manual-decision` adapter calls the same `decide(...)` as HTTP (same conditional UPDATE, outbox and recorder) and never writes `claim_approvals` directly (C3(a)).
- The decide UPDATE is a single conditional statement `... AND status IN ('ANALYSIS_PENDING','REVIEW_READY')`. In the same transaction, a 0-row result checks whether the row exists (404 if missing, 409 if it exists), per answer 9.
- Token handling:
  - the comparison uses `MessageDigest.isEqual`;
  - the token appears in no log statement;
  - startup fails when `INTERNAL_API_TOKEN` is blank.
  These are review items only; there's no test for them.
- V2 migration:
  - `decision_id UUID UNIQUE`;
  - the `claim_id` PK is unchanged;
  - `source` is AUTO or HUMAN;
  - the `decision_audit` columns are exactly the plan's;
  - `outbox` and `processed_events` match `common/.../db/outbox-inbox.sql`.
- `docker-compose.yml` has no `ports:` on `approval-service` and no gateway route to `/internal/**`. `.env.example` holds only a placeholder `INTERNAL_API_TOKEN`.
- **Moved out of 1A.3 (C3(d), C2; rev 10 1A.5 "Remove `claim.manual-decision` end to end" and its T1 grep `rg CLAIM_MANUAL_DECISION claim-service approval-service`).** Lead decided (21:21 IST) that **Agent A**, which owns approval-service, compose and `kafka/topics.txt`, removes the C2 fallback, the manual-decision adapter and its error handler, the listener, `recordManualDecision` and the topic in **one commit**. That commit lands with 1A.5 on the **combined branch**, and these greps are graded on that combined tip:
  - `rg "CLAIM_MANUAL_DECISION|recordManualDecision|ManualClaimDecisionEvent" approval-service` is empty;
  - the `claim.manual-decision` topic is removed;
  - `rg "without eventId"` is empty.
  In 1A.3 the listener, the adapter and the fallback **must still exist**.

## Grading (cumulative over the whole repo: root `mvn -B verify`, surefire + failsafe)
**Baseline source.** The Mac reports at `/workspace/mac-runs/1a2/d8baa7a/` show 125 executions with 0 failures, 0 errors and 0 skipped. `git diff d8baa7a 2fe08e4 -- '*/src/test/*' '*pom.xml'` is empty, so 2fe08e4 has the same counts.

**1A.2b B1/B2 status.** `ClaimServicePostgresWithoutDemoProfileTest` and `ClaimServicePostgresDemoProfileTest` are **not committed at 2fe08e4**. The 127 below assumes they land on the final 1A.2b tip.

**Table A: 1A.2b final tip (expected 127)**
| Module | surefire | failsafe |
|---|---|---|
| common | 88 | 0 |
| claim-service | 32 (30 + B1 + B2) | 0 |
| approval-service | 2 | 0 |
| settlement-service | 0 | 0 |
| notification-service | 5 | 0 |
| **Total** | **127** | **0** → **127** |

**Table B: old 1A.3 (spec v1 on the 127 base; this is rev 9's "150")**
| Module | surefire | failsafe |
|---|---|---|
| common | 88 | 0 |
| claim-service | 32 | 0 |
| approval-service | 4 (2 replaced + 2 case 3) | 21 |
| settlement-service | 0 | 0 |
| notification-service | 5 | 0 |
| **Total** | **129** | **21** → **150** |

**Table C: 1A.3 v2/v3 (156, for comparison)**
| Module | surefire | failsafe |
|---|---|---|
| common | 88 | 0 |
| claim-service | 32 | 0 |
| approval-service | 5 (2 replaced + 2 case 3 + 1 C2) | 26 |
| settlement-service | 0 | 0 |
| notification-service | 5 | 0 |
| **Total** | **130** | **26** → **156** |

**Table D: 1A.3 v7, this spec (expected 167)**
| Module | surefire | failsafe |
|---|---|---|
| common | 88 | 0 |
| claim-service | 32 | 0 |
| approval-service | 6 (2 replaced + 2 case 3 + 1 C2 + 1 startup) | 36 (26 + 9 validation cases + 1 backoff) |
| settlement-service | 0 | 0 |
| notification-service | 5 | 0 |
| **Total** | **131** | **36** → **167** |

**New in 1A.3 (v7): +25 methods, +40 executions (surefire +4, failsafe +36).** v4–v6 were +37 / 164; v7 adds the 3 validation cases. The two renamed methods replace existing ones, so they count 0 net.
- **Expected:** 167 executions, 0 failures, 0 errors, 0 skipped.
- Change from v2/v3: +8 executions. That is +1 surefire (startup) and +7 failsafe (6 validation cases + 1 backoff test).
- The plan's 1A.3 "150" (rev 9 and still in rev 10) is superseded by 167. Lead should update that line.
- Rev 10's 1A.2b grading line says "claim-service runs 127 tests". 127 is the whole-repo total; claim-service alone is 32 (Table A).

**Names that must appear in reports**, with every parameterized case:
- **failsafe** (`approval-service/target/failsafe-reports`, ApprovalDecisionIT):
  - submittedClaimIsAnalysisPendingAndNotApproved
  - humanLosesToSeededAutoApproval [APPROVED] [REJECTED]
  - secondDecideMatrix [APPROVED,APPROVED] [APPROVED,REJECTED] [REJECTED,APPROVED] [REJECTED,REJECTED]
  - approveOutboxPayloadCarriesDecidedByAgent
  - rejectOutboxPayloadCarriesDecidedByAgent
  - decideWithoutServiceTokenIs401
  - lookupWithoutServiceTokenIs401
  - lookupWithUserSessionCookieIs401
  - lookupWithUserTokenIs403
  - lookupNonGetMethodsAre405 [POST] [PUT] [DELETE] [PATCH]
  - lookupApprovedReturnsExactKeySet
  - lookupRejectedReturnsExactKeySet
  - lookupUnknownIdIs404
  - actuatorHealthOpenWithoutToken
  - partialApprovalAmountShowsOnLookupAndPayload
  - approveAboveClaimedAmountIs400
  - rejectWithApprovedAmountIs400
  - manualDecisionEventAdapterDecidesOnceThenConflicts
  - decideOnClaimWithoutWorkflowRowIs404
  - invalidDecisionBodyIs400 [amountZero] [amountNegative] [amountScaleThree] [roleNotAgent] [reasonBlank] [reasonTooLong] [malformedJson] [actionUnknown] [decidedByMissingNoWorkflow]
  - manualDecisionErrorHandlerUsesFixedBackOffAndLogsDrop
- **surefire, ApprovalServiceTest:**
  - doesNotApproveOnSubmitWithinTheLimit
  - doesNotApproveOnSubmitAboveTheLimit
  - replayedSameEventIdAddsNoRowNoEventAndLogsDuplicate
  - newEventIdSameClaimAddsNoRowNoEventAndLogsDuplicate
- **surefire, ClaimSubmittedListenerTest:**
  - missingEventIdHeaderUsesDerivedKey
- **surefire, InternalApiTokenStartupTest:**
  - blankServiceTokenFailsStartup
- **Must be absent:** autoApprovesClaimsWithinTheConfiguredLimit, leavesHighValueClaimsForManualReview.

**Pass** requires all of these:
- `mvn -B verify` is green on Himank's Mac and in free GitHub CI on the same commit (rev 9: Mac grading happens together with 1A.4; CI grades 1A.3 alone);
- every name above appears;
- per-module counts match Table D;
- the totals are exactly 167/0/0/0.

**Fail** on any of these:
- a missing or renamed test;
- a skipped or Docker-gated test;
- failsafe not wired;
- a total other than 167;
- an assertion weaker than written (containsAll, "at least", a choice of codes).

The race tests are counted in 1A.4. If they show up early, I report them separately, and they must still pass.

## Items placed in other specs (not counted here)
- **Answer 9, client half.** `ApprovalCommandClient` maps 404 `CLAIM_NOT_FOUND` to 409 `CLAIM_NOT_READY` with exactly 1 call. This goes in the **1A.5 spec**: the client is new claim-service code in 1A.5's file list, and 1A.3 adds no client. The 1A.6 `DecisionLookupClient` is a different client.
- **Answer 10.** Unknown-claimId `claim.approved` handling goes in the **1A.5 spec**. The answer file scopes it to 1A.5, and claim-service's `ClaimWorkflowListener` consumes `claim.approved`.

## Notes for Lead (non-blocking; the spec follows the answer file as written)
- **N1, C3(b) "email for now" contradicts the code.** At 2fe08e4, `ClaimService.decide` puts `agent.getFullName()`, not the email, into `ManualClaimDecisionEvent.decidedBy`. So in the interim, `decided_by_user_id` holds a full name. Test 18 uses the opaque string `agent-1a3@test.local` and asserts it is copied verbatim.
- **N2, the C3(a) adapter signature has no `reason`.** `decide(claimId, action, decidedBy, approvedAmount=null)` takes no reason, but `claim_approvals.reason` is NOT NULL (V1) and answer 2 requires a reason. The spec assumes the adapter passes `event.decisionReason()`, and test 18 asserts that.
- **N3, resolved by Lead decision 2.** The interim adapter uses a 2 s fixed backoff for 15 attempts, then logs ERROR `manual decision dropped claimId=<c>` (test 21).
- **N4, C3(c) vs answer 4.** C3(c) accepts that an adapter redelivery may add an extra CONFLICT row. That breaks answer 4's "a conflicted claim has exactly 1" for the adapter path only. Test 18 uses two distinct events, so it isn't affected.
- **N5, resolved.** Test 20 covers ≤ 0 (two cases), scale > 2, a non-AGENT role, a blank reason and a 501-character reason. Answer 1's blank-token startup is covered by `InternalApiTokenStartupTest`. The constant-time compare and the token never being logged stay review-only.
- **N6, resolved by Lead decision 1.** Agent A removes the fallback, adapter, listener and topic in one commit that lands with 1A.5 on the combined branch. The static greps check the combined tip. 1A.3 grades that they are present.
- **N7, resolved by Lead decision 3.** The lookup DTO is exactly `decisionId, claimId, action, approvedAmount, decidedBy`. Tests 11, 12 and 15 assert set equality, and Agent A's contract commit must match.

## Resolved by Lead (21:24 IST)
- **OQ-A:** `FixedBackOff(2000, 14)`: `getMaxAttempts() == 14`, so there are 15 deliveries in total (1 initial + 14 retries).
- **OQ-B:** the exact message is `manual decision dropped claimId=<c> alert=true`. `alert=true` always comes **last** in the message text. The same rule applies to every 1A.5 and 1A.6 alert line.
- **OQ-C:** the failure is an `IllegalStateException` whose message names `INTERNAL_API_TOKEN` and never contains the value.


## Count check against Agent A's tip 138a068 (v6; no spec change)
Source: `/workspace/patches/1a-3-138a068.bundle` (head `cursor/1a-3-approval-single-writer` = `138a0686e3df…`). Its net diff `1a-3-138a068-net.diff` has sha256 `2a77af5fd30dce5200044d822296aeddc541bdab70ed82b8cce0d1ca06e2cb5f`, verified, and equal to `git diff 2fe08e4 138a068`.
- **ApprovalDecisionIT** is at `approval-service/src/test/java/com/hvmanker/insurance/approval/integration/`. All **21** spec methods are present with exact names, giving **33 executions**:
  - `humanLosesToSeededAutoApproval` ×2;
  - `secondDecideMatrix` ×4 (CSV rows 301–304);
  - `lookupNonGetMethodsAre405` ×4;
  - `invalidDecisionBodyIs400` ×6: amountZero, amountNegative, amountScaleThree, roleNotAgent, reasonBlank, reasonTooLong (claims 341–346);
  - 17 single tests.
- **Surefire:**
  - `ApprovalServiceTest`: doesNotApproveOnSubmitWithinTheLimit, doesNotApproveOnSubmitAboveTheLimit, replayedSameEventIdAddsNoRowNoEventAndLogsDuplicate, newEventIdSameClaimAddsNoRowNoEventAndLogsDuplicate (the old names are gone);
  - `ClaimSubmittedListenerTest.missingEventIdHeaderUsesDerivedKey`;
  - `InternalApiTokenStartupTest.blankServiceTokenFailsStartup`.
  - That's **6**.
- **Missing: none. Misnamed: none. Extras: none.** The 1A.4 race tests are correctly absent.
- **Failsafe:** `maven-failsafe-plugin` 3.2.5 is in the root pluginManagement, with the `integration-test` and `verify` goals in `approval-service/pom.xml`. The classes dir is on the classpath and the repackaged jar is excluded.
- No `@Disabled`, `assume`, `disabledWithoutDocker` or `Thread.sleep`. The only `atMost` uses are bounded Awaitility commit waits (allowed).
- common and notification-service are unchanged. claim-service has only the B1/B2 additions.
- **Implied totals: common 88/0, claim-service 32/0, approval-service 6/33, settlement 0/0, notification 5/0, for 131/33 = 164. That matches Table D.**
- This check covers names and counts only, read from source. Assertion content is graded on the Mac run reports.

## Count check against Agent A's tip 0654e8e (v7)
Source: `/workspace/patches/1a-3-0654e8e-net.diff`, sha256 `478177c11c6727d0ac97a653130f2c05f263f99dcba170d8304b4f80519f7936`, equal to `git diff 2fe08e4 0654e8e`. There are 5 commits over 138a068: 001b71f, 70a0cd5, ad0a43a, 6db917d, 0654e8e.

**What changed vs 138a068:**
- `decide()` returns a `DecisionResult` (`DECIDED`/`CONFLICT`/`NOT_FOUND`) instead of throwing. The CONFLICT audit row therefore commits instead of being rolled back.
- `ClaimAlreadyDecidedException` and `ClaimWorkflowNotFoundException` are deleted.
- The Conflict, NotFound and Validation response records are unified into `ErrorResponse(code)`, whose key set is still `{code}`.
- Every 400 goes to `INVALID_DECISION_REQUEST`, including a new `HttpMessageNotReadableException` handler.
- `ApprovalService` uses a `TransactionTemplate`.
- `spring-boot-starter-validation` is removed. That's OK because validation is manual, in `InternalDecisionValidator`.
- The unit tests are adapted (TransactionTemplate, `FilterScanConfiguration`).
- The `manualDecisionBackOff`/`manualDecisionRecoverer`/`manualDecisionErrorHandler` beans and the drop line `manual decision dropped claimId={} alert=true` are unchanged.

**Tests:**
- `ApprovalDecisionIT` is **unchanged** (not in the diffstat).
- The 6 surefire names are unchanged.

**Compose:** `INTERNAL_API_TOKEN: ${INTERNAL_API_TOKEN:?}` is now present for **claim-service** (line 92), as well as approval-service (132) and settlement-service (159).

**Counts against v6:** 164 holds (common 88/0, claim-service 32/0, approval-service 6/33, settlement 0/0, notification 5/0). Nothing is missing, misnamed or extra.

**Against v7:**
- `invalidDecisionBodyIs400` is **missing 3 cases** (malformedJson, actionUnknown, decidedByMissingNoWorkflow), so the implied total is 164 against a bar of **167**.
- `decidedByMissingNoWorkflow` will FAIL until validation moves ahead of the lookup.
- The `@MethodSource` needs a `seedWorkflow` argument.
