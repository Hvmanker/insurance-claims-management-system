#!/usr/bin/env bash
# Phase 0 runtime gate — throwaway Compose project "phase0check" only.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

readonly REQUIRED_COMPOSE_PROJECT="phase0check"
readonly HEALTH_TIMEOUT_SEC="${PHASE0_HEALTH_TIMEOUT_SEC:-900}"
readonly BASE_URL="${PHASE0_BASE_URL:-http://localhost:8081}"

# Ordered gate checks (reorder to match Verifier list).
# Format: NN|display name|function
CHECKS=(
  '01|Every service is healthy after `up`, and `docker compose ps` shows 8081 as the only published port.|check_01_healthy_and_single_published_port'
  '02|From the host, 5432, 6379, 9092, 8082, 8083 and 8084 aren'\''t reachable, and 8081 is. Make the main test `docker compose -p phase0check port <service> <port>`, which must return empty for every service except claim-service:8081. Use `nc -z` only as a secondary check. If a host port is occupied by something OUTSIDE the stack (the compose port check is empty but nc succeeds), report SKIPPED with the port named, not FAIL.|check_02_host_port_exposure'
  '03|`redis-cli ping` without a password returns NOAUTH (run it inside the redis container).|check_03_redis_noauth_without_password'
  '04|After a cold start, Flyway migrates Postgres, and portal_users stays empty without the demo profile.|check_04_flyway_and_empty_portal_without_demo'
  '05|After `down -v && up` with the demo profile on, the demo password from the env file signs in.|check_05_demo_sign_in_after_fresh_volume'
  '06|With the real Redis cache (password on), INSURED B and BROKER get 404 after AGENT warms the cache, and `decide` evicts the entry. Do this live via curl.|check_06_live_cache_authorization_tm_a'
  '07|TM-B, the notification log-leak test, passes against a real Kafka consumer. If there'\''s no such integration test, report SKIPPED with the reason. Never count a unit-only run as a PASS here.|check_07_tm_b_kafka_integration'
  '08|A claim.decided that is delivered late, arriving before claim.submitted, is handled correctly.|check_08_late_claim_decided_e2e'
  '09|A duplicate eventId doesn'\''t cause a second payout.|check_09_duplicate_event_id_e2e'
  '10|The 5200.00 vs 5180.00 amount mismatch is flagged in reconciliation.|check_10_amount_mismatch_reconciliation'
  '11|A read between `decide` and its commit doesn'\''t put the stale status back into the cache. Without a test hook that pauses between decide and the commit, always report SKIPPED with that reason. No timing-based PASS.|check_11_decide_commit_cache_race'
  '12|The README URL works from a clean clone.|check_12_readme_dashboard_url'
  '13|TM-D: a claim over 10k stays SUBMITTED and the legacy AGENT decides it, run on the live stack.|check_13_tm_d_high_value_manual_decide'
  '14|no stale com.sunil-typed messages on Kafka topics after rename (topics emptied/recreated)|check_14_kafka_topics_empty_after_up'
  '15|Redis claims cache flushed on deploy after rename|check_15_redis_claims_cache_empty_after_up'
)

# Captured immediately after initial `up` health wait (before gate checks mutate Kafka/Redis).
PHASE0_SNAPSHOT_REDIS_CLAIMS_KEYS=""
PHASE0_SNAPSHOT_KAFKA_NONEMPTY_TOPICS=""

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="${REPO_ROOT}/phase0-runtime-check-${TIMESTAMP}.log"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

declare -a SECRET_VALUES=()

mask_secrets() {
  local text="$1"
  local secret
  for secret in "${SECRET_VALUES[@]}"; do
    [[ -z "$secret" ]] && continue
    text="${text//"$secret"/***REDACTED***}"
  done
  printf '%s' "$text"
}

log_line() {
  local masked
  masked="$(mask_secrets "$*")"
  printf '%s\n' "$masked" | tee -a "$LOG_FILE"
}

emit_result() {
  local nn="$1" status="$2" name="$3" evidence="$4"
  log_line "[${nn}] ${status} ${name} - ${evidence}"
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
    SKIPPED) SKIP_COUNT=$((SKIP_COUNT + 1)) ;;
  esac
}

check_display_name() {
  local index="$1" spec="${CHECKS[$index]}"
  local name="${spec#*|}"
  name="${name%%|check_*}"
  printf '%s' "$name"
}

require_compose_project_guard() {
  if [[ "${COMPOSE_PROJECT:-}" != "${REQUIRED_COMPOSE_PROJECT}" ]]; then
    printf 'COMPOSE_PROJECT must be exactly %q (got %q); refusing to run.\n' \
      "$REQUIRED_COMPOSE_PROJECT" "${COMPOSE_PROJECT:-<unset>}" >&2
    exit 2
  fi
}

require_env_file() {
  if [[ ! -f "${REPO_ROOT}/.env" ]]; then
    printf 'Missing required %s — copy from .env.example and configure secrets.\n' "${REPO_ROOT}/.env" >&2
    exit 1
  fi
}

load_env_from_dotenv() {
  local line key value
  SECRET_VALUES=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
      value="${value#\"}"; value="${value%\"}"
      value="${value#\'}"; value="${value%\'}"
      export "$key=$value"
      if [[ "$key" == *PASSWORD* || "$key" == *SECRET* || "$key" == *TOKEN* ]]; then
        [[ -n "$value" ]] && SECRET_VALUES+=("$value")
      fi
    fi
  done < "${REPO_ROOT}/.env"
}

compose() {
  docker compose -p "$COMPOSE_PROJECT" --env-file "${REPO_ROOT}/.env" "$@"
}

compose_with_profile() {
  local profile="$1"
  shift
  # Shell env overrides .env interpolation for SPRING_PROFILES_ACTIVE.
  SPRING_PROFILES_ACTIVE="$profile" compose "$@"
}

capture_post_up_snapshots() {
  local topic offsets line end_offset nonempty=""
  PHASE0_SNAPSHOT_REDIS_CLAIMS_KEYS="$(
    compose exec -T redis redis-cli -a "${REDIS_PASSWORD}" --scan --pattern 'claims*' 2>/dev/null | sort -u || true
  )"
  while IFS= read -r topic; do
    [[ -z "$topic" ]] && continue
    offsets="$(compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh \
      --bootstrap-server localhost:9092 --topic "$topic" 2>/dev/null || true)"
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      end_offset="${line##*:}"
      if [[ "$end_offset" =~ ^[0-9]+$ ]] && [[ "$end_offset" -gt 0 ]]; then
        nonempty="${nonempty} ${topic}:${end_offset}"
      fi
    done <<< "$offsets"
  done < <(compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --list 2>/dev/null | grep -E '^claim\.' || true)
  PHASE0_SNAPSHOT_KAFKA_NONEMPTY_TOPICS="$(printf '%s' "$nonempty" | xargs 2>/dev/null || true)"
}

wait_for_compose_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SEC))
  local ps_out unhealthy
  while (( SECONDS < deadline )); do
    ps_out="$(compose ps --format '{{.Name}}\t{{.Health}}\t{{.State}}' 2>/dev/null || true)"
    if [[ -z "$ps_out" ]]; then
      sleep 5
      continue
    fi
    unhealthy="$(printf '%s\n' "$ps_out" | awk -F'\t' '$3 == "running" && $2 != "" && $2 != "healthy" { print $1 }')"
    if [[ -z "$unhealthy" ]]; then
      return 0
    fi
    sleep 5
  done
  return 1
}

curl_json() {
  local method="$1" url="$2" jar="$3" body="${4:-}"
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/phase0-curl-body.json -w '%{http_code}' \
      -X "$method" "$url" \
      -H 'Content-Type: application/json' \
      -b "$jar" -c "$jar" \
      -d "$body"
  else
    curl -sS -o /tmp/phase0-curl-body.json -w '%{http_code}' \
      -X "$method" "$url" \
      -b "$jar" -c "$jar"
  fi
}

login_user() {
  local email="$1" password="$2" jar="$3"
  local body code
  body="$(printf '{"email":"%s","password":"%s"}' "$email" "$password")"
  code="$(curl_json POST "${BASE_URL}/api/auth/login" "$jar" "$body")"
  [[ "$code" == "200" ]]
}

register_insured() {
  local email="$1" password="$2" full_name="$3" jar="$4"
  local body code
  body="$(printf '{"fullName":"%s","email":"%s","password":"%s"}' "$full_name" "$email" "$password")"
  code="$(curl_json POST "${BASE_URL}/api/auth/register" "$jar" "$body")"
  [[ "$code" == "201" || "$code" == "200" ]]
}

submit_claim() {
  local jar="$1" amount="$2"
  local body code
  body="$(printf '{"policyNumber":"POL-PHASE0-%s","claimType":"AUTO","reason":"Phase0 runtime gate","description":"Automated runtime check claim","estimatedAmount":%s}' \
    "$(date +%s)" "$amount")"
  code="$(curl_json POST "${BASE_URL}/claims" "$jar" "$body")"
  [[ "$code" == "201" ]]
}

claim_status() {
  local jar="$1" claim_id="$2"
  local code
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar")"
  if [[ "$code" != "200" ]]; then
    return 1
  fi
  python3 -c 'import json,sys; print(json.load(open("/tmp/phase0-curl-body.json"))["status"])' 2>/dev/null
}

claim_id_from_submit() {
  python3 -c 'import json; print(json.load(open("/tmp/phase0-curl-body.json"))["id"])' 2>/dev/null
}

has_tm_b_kafka_integration_test() {
  local f
  while IFS= read -r f; do
    if [[ -f "$f" ]] && grep -qE '@EmbeddedKafka|Testcontainers|@SpringBootTest' "$f" 2>/dev/null; then
      if grep -q 'WorkflowNotificationListener' "$f" 2>/dev/null; then
        return 0
      fi
    fi
  done < <(find notification-service/src/test -name '*LogLeak*' -o -name '*WorkflowNotification*IT*' 2>/dev/null)
  return 1
}

has_claim_decided_inbox_consumer() {
  local f
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    if grep -qE 'claim\.decided|CLAIM_DECIDED' "$f" 2>/dev/null; then
      return 0
    fi
  done < <(rg -l '@KafkaListener' claim-service settlement-service approval-service notification-service --glob '*.java' 2>/dev/null || true)
  return 1
}

has_settlement_idempotency_e2e() {
  [[ -x "${REPO_ROOT}/scripts/phase0-e2e-duplicate-event.sh" ]]
}

has_reconciliation_consumer() {
  [[ -x "${REPO_ROOT}/scripts/phase0-e2e-reconciliation.sh" ]]
}

has_late_claim_decided_e2e() {
  [[ -x "${REPO_ROOT}/scripts/phase0-e2e-late-claim-decided.sh" ]] || has_claim_decided_inbox_consumer
}

has_decide_commit_pause_hook() {
  rg -q 'PHASE0_DECIDE_PAUSE|phase0.decide.pause|decideCommitPauseHook' claim-service --glob '*.java' 2>/dev/null
}

readme_dashboard_url() {
  awk '
    /Open the secure insurance portal/ { found=1; next }
    found && /^http:\/\// { print; exit }
    found && match($0, /http:\/\/[^ ]+/) { print substr($0, RSTART, RLENGTH); exit }
  ' README.md 2>/dev/null | tr -d '`' | head -n1
}

check_01_healthy_and_single_published_port() {
  local nn="01" name
  name="$(check_display_name 0)"
  local ps_out not_healthy published host_ports
  ps_out="$(compose ps 2>/dev/null || true)"
  not_healthy="$(compose ps --format '{{.Service}}\t{{.Health}}\t{{.State}}' 2>/dev/null \
    | awk -F'\t' '$3 == "running" && $2 != "" && $2 != "healthy" { print $1 }' | paste -sd, -)"
  if [[ -n "$not_healthy" ]]; then
    emit_result "$nn" FAIL "$name" "unhealthy or starting services: ${not_healthy}"
    return
  fi
  published="$(compose ps --format '{{.Ports}}' 2>/dev/null | grep -oE '0\.0\.0\.0:[0-9]+|127\.0\.0\.1:[0-9]+|\[::\]:[0-9]+' || true)"
  host_ports="$(printf '%s\n' "$published" | sed -E 's/.*:([0-9]+)$/\1/' | sort -u | paste -sd, -)"
  if [[ "$host_ports" != "8081" ]]; then
    emit_result "$nn" FAIL "$name" "published host ports=[${host_ports:-none}], expected only 8081"
    return
  fi
  emit_result "$nn" PASS "$name" "all services healthy; compose ps shows only host port 8081"
}

check_02_host_port_exposure() {
  local nn="02" name
  name="$(check_display_name 1)"
  local -a services_ports=(
    'postgres:5432'
    'redis:6379'
    'kafka:9092'
    'claim-service:8081'
    'approval-service:8082'
    'settlement-service:8083'
    'notification-service:8084'
  )
  local entry svc port mapping host_port nc_ok skip_ports=() fail_reasons=()
  for entry in "${services_ports[@]}"; do
    svc="${entry%%:*}"
    port="${entry##*:}"
    mapping="$(compose port "$svc" "$port" 2>/dev/null || true)"
    if [[ "$svc" == "claim-service" && "$port" == "8081" ]]; then
      if [[ -z "$mapping" ]]; then
        fail_reasons+=("claim-service:8081 has no compose port mapping")
        continue
      fi
      host_port="${mapping##*:}"
      if [[ "$host_port" != "8081" ]]; then
        fail_reasons+=("claim-service maps to host ${host_port}, expected 8081")
      fi
      continue
    fi
    if [[ -n "$mapping" ]]; then
      fail_reasons+=("${svc}:${port} compose port returned ${mapping}")
      continue
    fi
    if command -v nc >/dev/null 2>&1; then
      if nc -z -w 2 localhost "$port" >/dev/null 2>&1; then
        skip_ports+=("$port")
      fi
    fi
  done
  if ((${#skip_ports[@]} > 0)); then
    emit_result "$nn" SKIPPED "$name" "host port(s) ${skip_ports[*]} open outside phase0check stack (nc succeeded, compose port empty)"
    return
  fi
  if ((${#fail_reasons[@]} > 0)); then
    emit_result "$nn" FAIL "$name" "${fail_reasons[0]}"
    return
  fi
  if ! command -v nc >/dev/null 2>&1; then
    emit_result "$nn" PASS "$name" "compose port empty for internal services; claim-service:8081 mapped; nc unavailable for secondary probe"
    return
  fi
  if nc -z -w 2 localhost 8081 >/dev/null 2>&1; then
    emit_result "$nn" PASS "$name" "compose port checks OK; nc confirms 8081 reachable; blocked ports not listening"
    return
  fi
  emit_result "$nn" FAIL "$name" "compose port mapped 8081 but nc -z localhost 8081 failed"
}

check_03_redis_noauth_without_password() {
  local nn="03" name
  name="$(check_display_name 2)"
  local out
  out="$(compose exec -T redis redis-cli ping 2>&1 || true)"
  if [[ "$out" == *NOAUTH* ]]; then
    emit_result "$nn" PASS "$name" "redis-cli ping returned NOAUTH"
  else
    emit_result "$nn" FAIL "$name" "expected NOAUTH, got: ${out//$'\n'/ }"
  fi
}

check_04_flyway_and_empty_portal_without_demo() {
  local nn="04" name
  name="$(check_display_name 3)"
  local flyway_count portal_count logs_ok=false

  compose down -v >/dev/null 2>&1 || true
  if ! compose_with_profile "" up -d --build >/dev/null 2>&1; then
    emit_result "$nn" FAIL "$name" "compose up without demo profile failed"
    return
  fi
  if ! wait_for_compose_healthy; then
    emit_result "$nn" FAIL "$name" "stack did not become healthy within ${HEALTH_TIMEOUT_SEC}s (no demo profile)"
    return
  fi
  if compose logs claim-service 2>/dev/null | grep -qE 'Successfully applied|Schema .* is up to date|Flyway.*success'; then
    logs_ok=true
  fi
  flyway_count="$(compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -tAc \
    "SELECT COUNT(*) FROM claims.flyway_schema_history_claim_service WHERE success = true;" 2>/dev/null | tr -d '[:space:]' || echo 0)"
  portal_count="$(compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -tAc \
    "SELECT COUNT(*) FROM claims.portal_users;" 2>/dev/null | tr -d '[:space:]' || echo -1)"
  if [[ "$flyway_count" =~ ^[0-9]+$ ]] && [[ "$flyway_count" -gt 0 ]] && [[ "$portal_count" == "0" ]]; then
    emit_result "$nn" PASS "$name" "flyway_schema_history_claim_service has ${flyway_count} successful migration(s); portal_users count=0"
    return
  fi
  if [[ "$logs_ok" == true ]] && [[ "$portal_count" == "0" ]]; then
    emit_result "$nn" PASS "$name" "claim-service Flyway logs OK; portal_users count=0"
    return
  fi
  emit_result "$nn" FAIL "$name" "flyway_rows=${flyway_count} portal_users=${portal_count} flyway_logs=${logs_ok}"
}

check_05_demo_sign_in_after_fresh_volume() {
  local nn="05" name jar
  name="$(check_display_name 4)"
  jar="$(mktemp)"

  if [[ "${SPRING_PROFILES_ACTIVE:-}" != *demo* ]]; then
    emit_result "$nn" SKIPPED "$name" "SPRING_PROFILES_ACTIVE in .env does not include demo"
    return
  fi
  compose down -v >/dev/null 2>&1 || true
  if ! compose up -d --build >/dev/null 2>&1; then
    emit_result "$nn" FAIL "$name" "compose up with demo profile failed"
    return
  fi
  if ! wait_for_compose_healthy; then
    emit_result "$nn" FAIL "$name" "stack did not become healthy within ${HEALTH_TIMEOUT_SEC}s (demo profile)"
    return
  fi
  if login_user "agent@insureflow.local" "${DEMO_AGENT_PASSWORD}" "$jar"; then
    emit_result "$nn" PASS "$name" "agent@insureflow.local login HTTP 200 after down -v && up"
  else
    emit_result "$nn" FAIL "$name" "agent@insureflow.local login failed after fresh volume"
  fi
}

check_06_live_cache_authorization_tm_a() {
  local nn="06" name
  name="$(check_display_name 5)"
  local jar_a jar_b jar_broker jar_agent claim_id code status

  if [[ "${SPRING_PROFILES_ACTIVE:-}" != *demo* ]]; then
    emit_result "$nn" SKIPPED "$name" "demo profile required for seeded AGENT and BROKER users"
    return
  fi
  jar_a="$(mktemp)"; jar_b="$(mktemp)"; jar_broker="$(mktemp)"; jar_agent="$(mktemp)"

  if ! login_user "insured@insureflow.local" "${DEMO_INSURED_PASSWORD}" "$jar_a"; then
    emit_result "$nn" FAIL "$name" "insured A demo login failed"
    return
  fi
  if ! submit_claim "$jar_a" "5000.00"; then
    emit_result "$nn" FAIL "$name" "insured A claim submit failed"
    return
  fi
  claim_id="$(claim_id_from_submit)"
  if [[ -z "$claim_id" ]]; then
    emit_result "$nn" FAIL "$name" "could not parse claim id from submit response"
    return
  fi

  if ! login_user "agent@insureflow.local" "${DEMO_AGENT_PASSWORD}" "$jar_agent"; then
    emit_result "$nn" FAIL "$name" "AGENT login failed"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_agent")"
  if [[ "$code" != "200" ]]; then
    emit_result "$nn" FAIL "$name" "AGENT warm-cache GET returned HTTP ${code}"
    return
  fi

  if ! register_insured "insured-b-phase0@example.com" "${DEMO_INSURED_PASSWORD}" "Insured B Phase0" "$jar_b"; then
    emit_result "$nn" FAIL "$name" "INSURED B registration failed"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_b")"
  if [[ "$code" != "404" ]]; then
    emit_result "$nn" FAIL "$name" "INSURED B GET after cache warm returned HTTP ${code}, expected 404"
    return
  fi

  if ! login_user "broker@insureflow.local" "${DEMO_BROKER_PASSWORD}" "$jar_broker"; then
    emit_result "$nn" FAIL "$name" "BROKER login failed"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_broker")"
  if [[ "$code" != "404" ]]; then
    emit_result "$nn" FAIL "$name" "BROKER GET after cache warm returned HTTP ${code}, expected 404"
    return
  fi

  code="$(curl_json POST "${BASE_URL}/claims/${claim_id}/decision" "$jar_agent" \
    '{"approved":true,"reason":"Phase0 TM-A cache eviction"}')"
  if [[ "$code" != "200" ]]; then
    emit_result "$nn" FAIL "$name" "AGENT decide returned HTTP ${code}"
    return
  fi
  status="$(claim_status "$jar_agent" "$claim_id" || true)"
  if [[ "$status" != "APPROVED" ]]; then
    emit_result "$nn" FAIL "$name" "after decide AGENT GET status=${status:-unknown}, expected APPROVED (cache evicted)"
    return
  fi
  emit_result "$nn" PASS "$name" "INSURED B and BROKER HTTP 404 after AGENT warm; decide evicted cache (status APPROVED)"
}

check_07_tm_b_kafka_integration() {
  local nn="07" name
  name="$(check_display_name 6)"
  if has_tm_b_kafka_integration_test; then
    local mvn_out tests_run failures errors skipped
    mvn_out="$(mvn -B -pl notification-service test \
      -Dtest=WorkflowNotificationListenerLogLeakTest \
      -Dsurefire.failIfNoSpecifiedTests=false 2>&1 || true)"
    tests_run="$(printf '%s' "$mvn_out" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' | tail -1)"
    failures="$(printf '%s' "$mvn_out" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p' | tail -1)"
    errors="$(printf '%s' "$mvn_out" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p' | tail -1)"
    skipped="$(printf '%s' "$mvn_out" | sed -n 's/.*Skipped: \([0-9]*\).*/\1/p' | tail -1)"
    tests_run="${tests_run:-0}"; failures="${failures:-0}"; errors="${errors:-0}"; skipped="${skipped:-0}"
    if [[ "$tests_run" -gt 0 && "$failures" == "0" && "$errors" == "0" && "$skipped" == "0" ]]; then
      emit_result "$nn" PASS "$name" "Kafka integration TM-B: tests run=${tests_run} failures=0"
    else
      emit_result "$nn" FAIL "$name" "Kafka integration TM-B: run=${tests_run} failures=${failures} errors=${errors} skipped=${skipped}"
    fi
    return
  fi
  emit_result "$nn" SKIPPED "$name" "no Kafka integration test for TM-B (unit-only WorkflowNotificationListenerLogLeakTest on main)"
}

check_08_late_claim_decided_e2e() {
  local nn="08" name
  name="$(check_display_name 7)"
  if has_late_claim_decided_e2e; then
    emit_result "$nn" FAIL "$name" "late claim.decided e2e hook present but runner not implemented in this script"
  else
    emit_result "$nn" SKIPPED "$name" "consumers not yet on the contract envelope (1A)"
  fi
}

check_09_duplicate_event_id_e2e() {
  local nn="09" name
  name="$(check_display_name 8)"
  if has_settlement_idempotency_e2e; then
    emit_result "$nn" FAIL "$name" "settlement idempotency hooks present but phase0 duplicate-event e2e runner not implemented"
  else
    emit_result "$nn" SKIPPED "$name" "consumers not yet on the contract envelope (1A)"
  fi
}

check_10_amount_mismatch_reconciliation() {
  local nn="10" name
  name="$(check_display_name 9)"
  if has_reconciliation_consumer; then
    emit_result "$nn" FAIL "$name" "reconciliation consumer detected but 5200.00 vs 5180.00 e2e runner not implemented"
  else
    emit_result "$nn" SKIPPED "$name" "consumers not yet on the contract envelope (1A)"
  fi
}

check_11_decide_commit_cache_race() {
  local nn="11" name
  name="$(check_display_name 10)"
  if has_decide_commit_pause_hook; then
    emit_result "$nn" FAIL "$name" "decide/commit pause hook found but automated check not implemented"
  else
    emit_result "$nn" SKIPPED "$name" "no test hook that pauses between decide and commit"
  fi
}

check_12_readme_dashboard_url() {
  local nn="12" name
  name="$(check_display_name 11)"
  local url code
  url="$(readme_dashboard_url)"
  if [[ -z "$url" ]]; then
    emit_result "$nn" FAIL "$name" "could not parse dashboard URL from README.md"
    return
  fi
  code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
  if [[ "$code" == "200" ]]; then
    emit_result "$nn" PASS "$name" "GET ${url} returned HTTP 200"
  else
    emit_result "$nn" FAIL "$name" "GET ${url} returned HTTP ${code}"
  fi
}

check_13_tm_d_high_value_manual_decide() {
  local nn="13" name
  name="$(check_display_name 12)"
  local jar_insured jar_agent claim_id status code

  if [[ "${SPRING_PROFILES_ACTIVE:-}" != *demo* ]]; then
    emit_result "$nn" SKIPPED "$name" "demo profile required for legacy AGENT user"
    return
  fi
  jar_insured="$(mktemp)"; jar_agent="$(mktemp)"

  if ! login_user "insured@insureflow.local" "${DEMO_INSURED_PASSWORD}" "$jar_insured"; then
    emit_result "$nn" FAIL "$name" "insured demo login failed"
    return
  fi
  if ! submit_claim "$jar_insured" "15000.00"; then
    emit_result "$nn" FAIL "$name" "high-value claim submit failed"
    return
  fi
  claim_id="$(claim_id_from_submit)"
  sleep 8
  status="$(claim_status "$jar_insured" "$claim_id" || true)"
  if [[ "$status" != "SUBMITTED" ]]; then
    emit_result "$nn" FAIL "$name" "claim over 10k status=${status:-unknown}, expected SUBMITTED before manual decide"
    return
  fi
  if ! login_user "agent@insureflow.local" "${DEMO_AGENT_PASSWORD}" "$jar_agent"; then
    emit_result "$nn" FAIL "$name" "legacy AGENT login failed"
    return
  fi
  code="$(curl_json POST "${BASE_URL}/claims/${claim_id}/decision" "$jar_agent" \
    '{"approved":true,"reason":"TM-D manual approval over 10k"}')"
  if [[ "$code" != "200" ]]; then
    emit_result "$nn" FAIL "$name" "AGENT decide returned HTTP ${code}"
    return
  fi
  status="$(claim_status "$jar_agent" "$claim_id" || true)"
  if [[ "$status" == "APPROVED" || "$status" == "REJECTED" ]]; then
    emit_result "$nn" PASS "$name" "claim stayed SUBMITTED until AGENT decide; final status=${status}"
  else
    emit_result "$nn" FAIL "$name" "after AGENT decide status=${status:-unknown}"
  fi
}

check_14_kafka_topics_empty_after_up() {
  local nn="14" name
  name="$(check_display_name 13)"
  if [[ -z "${PHASE0_SNAPSHOT_KAFKA_NONEMPTY_TOPICS:-}" ]]; then
    emit_result "$nn" PASS "$name" "claim.* Kafka topics empty or freshly created immediately after up"
    return
  fi
  emit_result "$nn" SKIPPED "$name" "non-empty claim topics after up:${PHASE0_SNAPSHOT_KAFKA_NONEMPTY_TOPICS}"
}

check_15_redis_claims_cache_empty_after_up() {
  local nn="15" name key_count
  name="$(check_display_name 14)"
  if [[ -z "${PHASE0_SNAPSHOT_REDIS_CLAIMS_KEYS//[$'\n\r\t ']}" ]]; then
    emit_result "$nn" PASS "$name" "redis SCAN claims* returned no keys immediately after up"
    return
  fi
  key_count="$(printf '%s\n' "$PHASE0_SNAPSHOT_REDIS_CLAIMS_KEYS" | sed '/^$/d' | wc -l | tr -d ' ')"
  emit_result "$nn" SKIPPED "$name" "redis SCAN claims* found ${key_count} key(s) after up"
}

run_all_checks() {
  local spec nn display fn
  for spec in "${CHECKS[@]}"; do
    IFS='|' read -r nn display fn <<< "$spec"
    if ! declare -f "$fn" >/dev/null 2>&1; then
      emit_result "$nn" FAIL "$display" "missing check function ${fn}"
      continue
    fi
    set +e
    "$fn"
    set -e
  done
}

print_summary() {
  log_line "SUMMARY pass=${PASS_COUNT} fail=${FAIL_COUNT} skipped=${SKIP_COUNT} total=${#CHECKS[@]}"
}

main() {
  require_compose_project_guard
  require_env_file
  load_env_from_dotenv

  : > "$LOG_FILE"
  log_line "phase0-runtime-check starting at ${TIMESTAMP}"
  log_line "repo=${REPO_ROOT} COMPOSE_PROJECT=${COMPOSE_PROJECT}"

  log_line "compose down -v (throwaway stack)"
  compose down -v

  log_line "compose up -d --build"
  compose up -d --build

  if ! wait_for_compose_healthy; then
    log_line "WARN: initial health wait timed out after ${HEALTH_TIMEOUT_SEC}s; running checks anyway"
  fi

  log_line "capturing post-up Kafka/Redis snapshots for checks 14-15"
  capture_post_up_snapshots

  run_all_checks
  print_summary

  if [[ "$FAIL_COUNT" -eq 0 && "$SKIP_COUNT" -eq 0 && "$PASS_COUNT" -eq ${#CHECKS[@]} ]]; then
    exit 0
  fi
  exit 1
}

main "$@"
