#!/usr/bin/env bash
# Phase 0 runtime gate — throwaway Compose project "phase0check" only.
# shellcheck disable=SC2317,SC2329,SC2016 # CHECKS entries are literal names; check_* functions run via CHECKS dispatch
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

readonly REQUIRED_COMPOSE_PROJECT="phase0check"
readonly HEALTH_TIMEOUT_SEC="${PHASE0_HEALTH_TIMEOUT_SEC:-900}"
readonly CLAIM_HTTP_READY_TIMEOUT_SEC="${PHASE0_CLAIM_HTTP_READY_TIMEOUT_SEC:-180}"
readonly BASE_URL="${PHASE0_BASE_URL:-http://localhost:8081}"
readonly DEMO_SEEDER_LOG_MARKER='Demo portal user ready:'
# DEMO_* names referenced by demo-profile gate checks (must exist in .env when demo is active).
readonly SCRIPT_DEMO_ENV_VARS=(
  DEMO_AGENT_PASSWORD
  DEMO_INSURED_PASSWORD
  DEMO_BROKER_PASSWORD
)

# Ordered gate checks (reorder to match Verifier list).
# Format: NN|display name|handler|stub_skip_reason
# Use handler "." with stub_skip_reason for checks run inline in run_all_checks (08–11, 14–15).
CHECKS=(
  '01|Every service is healthy after `up`, and `docker compose ps` shows 8081 as the only published port.|check_01_healthy_and_single_published_port'
  '02|From the host, 5432, 6379, 9092, 8082, 8083 and 8084 aren'\''t reachable, and 8081 is. Make the main test `docker compose -p phase0check port <service> <port>`, which must return empty for every service except claim-service:8081. Use `nc -z` only as a secondary check. If a host port is occupied by something OUTSIDE the stack (the compose port check is empty but nc succeeds), report SKIPPED with the port named, not FAIL.|check_02_host_port_exposure'
  '03|`redis-cli ping` without a password returns NOAUTH (run it inside the redis container).|check_03_redis_noauth_without_password'
  '04|After a cold start, Flyway migrates Postgres, and portal_users stays empty without the demo profile.|check_04_flyway_and_empty_portal_without_demo'
  '05|After `down -v && up` with the demo profile on, the demo password from the env file signs in.|check_05_demo_sign_in_after_fresh_volume'
  '06|With the real Redis cache (password on), INSURED B and BROKER get 404 after AGENT warms the cache, and `decide` evicts the entry. Do this live via curl.|check_06_live_cache_authorization_tm_a'
  '07|TM-B, the notification log-leak test, passes against a real Kafka consumer. If there'\''s no such integration test, report SKIPPED with the reason. Never count a unit-only run as a PASS here.|check_07_tm_b_kafka_integration'
  '08|A claim.decided that is delivered late, arriving before claim.submitted, is handled correctly.|.|consumers not yet on the contract envelope (1A)'
  '09|A duplicate eventId doesn'\''t cause a second payout.|.|consumers not yet on the contract envelope (1A)'
  '10|The 5200.00 vs 5180.00 amount mismatch is flagged in reconciliation.|.|consumers not yet on the contract envelope (1A)'
  '11|A read between `decide` and its commit doesn'\''t put the stale status back into the cache. Without a test hook that pauses between decide and the commit, always report SKIPPED with that reason. No timing-based PASS.|.|no hook to pause between decide and its commit'
  '12|The README URL works from a clean clone.|check_12_readme_dashboard_url|'
  '13|TM-D: a claim over 10k stays SUBMITTED and the legacy AGENT decides it, run on the live stack.|check_13_tm_d_high_value_manual_decide|'
  # Checks 14–15: never PASS on the default `down -v` then `up` path — empty Kafka/Redis is
  # guaranteed and would be a false green. A real upgrade test must: (1) start a pre-rename
  # image stack, warm Redis with a cached claim entry, and publish at least one legacy
  # com.sunil-typed Kafka event; (2) run the documented topic reset / Redis claims flush;
  # (3) deploy new images and verify they start, serve a claim read, and consume new events
  # without deserialization errors.
  '14|no stale com.sunil-typed messages on Kafka topics after rename (topics emptied/recreated)|.|upgrade path not exercised'
  '15|Redis claims cache flushed on deploy after rename|.|upgrade path not exercised'
)

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="${REPO_ROOT}/phase0-runtime-check-${TIMESTAMP}.log"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

declare -a SECRET_VALUES=()
declare -a PHASE0_TMP_FILES=()
PHASE0_CURL_BODY=""
PHASE0_LAST_HTTP_CODE=""
COMPOSE_PS_STATUS_REPORT=""

phase0_register_tmp() {
  PHASE0_TMP_FILES+=("$1")
}

phase0_mktemp() {
  local tmp
  tmp="$(mktemp)"
  phase0_register_tmp "$tmp"
  printf '%s' "$tmp"
}

phase0_cleanup_tmps() {
  local f
  for f in ${PHASE0_TMP_FILES[@]+"${PHASE0_TMP_FILES[@]}"}; do
    rm -f "$f"
  done
}

mask_secrets() {
  local text="$1"
  local secret
  for secret in ${SECRET_VALUES[@]+"${SECRET_VALUES[@]}"}; do
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

demo_profile_enabled() {
  [[ "${SPRING_PROFILES_ACTIVE:-}" == *demo* ]]
}

require_demo_env_vars_when_enabled() {
  local var missing=()
  if ! demo_profile_enabled; then
    return 0
  fi
  for var in "${SCRIPT_DEMO_ENV_VARS[@]}"; do
    if [[ -z "${!var:-}" ]]; then
      missing+=("$var")
    fi
  done
  if ((${#missing[@]} > 0)); then
    printf 'Missing required demo environment variables: %s\n' "${missing[*]}" >&2
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

# `docker compose port` may return empty or a binding ending in `:0` when the
# container port is not published on the host; treat both as unpublished.
compose_port_is_published() {
  local mapping="$1"
  [[ -n "$mapping" ]] && [[ "$mapping" != *:0 ]]
}

log_repo_git_state() {
  local head porcelain
  head="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
  porcelain="$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null || true)"
  log_line "git rev-parse HEAD: ${head}"
  log_line "git status --porcelain"
  if [[ -z "$porcelain" ]]; then
    log_line "(clean)"
  else
    while IFS= read -r line; do
      [[ -n "$line" ]] && log_line "$line"
    done <<< "$porcelain"
  fi
}

redis_scan_claims_keys() {
  compose exec -T -e REDISCLI_AUTH="${REDIS_PASSWORD}" redis \
    redis-cli --scan --pattern 'claims*' 2>/dev/null || true
}

redis_scan_contains_claim_id() {
  local claim_id="$1" scan_out
  scan_out="$(redis_scan_claims_keys)"
  grep -qF "$claim_id" <<< "$scan_out" 2>/dev/null
}

log_check_06_redis_scan() {
  local phase="$1" scan_out line logged=0
  scan_out="$(redis_scan_claims_keys)"
  if [[ -z "${scan_out//[$'\n\r\t ']}" ]]; then
    log_line "[06] redis-scan ${phase}: (none)"
    return
  fi
  while IFS= read -r line; do
    if [[ -n "$line" ]]; then
      log_line "[06] redis-scan ${phase}: ${line}"
      logged=1
    fi
  done <<< "$scan_out"
  if [[ "$logged" -eq 0 ]]; then
    log_line "[06] redis-scan ${phase}: (none)"
  fi
}

compose_ps_lines() {
  compose ps -a --format '{{.Service}}\t{{.State}}\t{{.Health}}' 2>/dev/null || true
}

compose_ps_status_report() {
  local svc state health
  local -a parts=()
  while IFS=$'\t' read -r svc state health; do
    [[ -z "$svc" ]] && continue
    parts+=("${svc}: state=${state} health=${health:-<empty>}")
  done <<< "$(compose_ps_lines)"
  local IFS='; '
  printf '%s' "${parts[*]}"
}

compose_all_services_running_healthy() {
  local svc state health saw_service=0
  while IFS=$'\t' read -r svc state health; do
    [[ -z "$svc" ]] && continue
    saw_service=1
    if [[ "$state" != "running" ]] || [[ "$health" != "healthy" ]]; then
      return 1
    fi
  done <<< "$(compose_ps_lines)"
  [[ "$saw_service" -eq 1 ]]
}

unhealthy_services() {
  local svc state health
  local -a parts=()
  while IFS=$'\t' read -r svc state health; do
    [[ -z "$svc" ]] && continue
    if [[ "$state" != "running" ]] || [[ "$health" != "healthy" ]]; then
      parts+=("${svc}(state=${state},health=${health:-empty})")
    fi
  done <<< "$(compose_ps_lines)"
  local IFS=','
  printf '%s' "${parts[*]}"
}

wait_for_compose_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SEC))
  COMPOSE_PS_STATUS_REPORT=""
  while (( SECONDS < deadline )); do
    if compose_all_services_running_healthy; then
      return 0
    fi
    sleep 5
  done
  COMPOSE_PS_STATUS_REPORT="$(compose_ps_status_report)"
  log_line "ERROR: compose health wait timed out after ${HEALTH_TIMEOUT_SEC}s"
  log_line "compose ps -a: ${COMPOSE_PS_STATUS_REPORT}"
  return 1
}

normalize_http_code() {
  local code="$1"
  if [[ "$code" =~ ^[0-9]{3}$ ]]; then
    printf '%s' "$code"
  else
    printf '%s' "000"
  fi
}

curl_http_code() {
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' "$@" 2>/dev/null)" || code=""
  code="$(normalize_http_code "$code")"
  PHASE0_LAST_HTTP_CODE="$code"
  printf '%s' "$code"
}

wait_for_claim_service_http() {
  local deadline=$((SECONDS + CLAIM_HTTP_READY_TIMEOUT_SEC))
  local body
  while (( SECONDS < deadline )); do
    body="$(curl -sS -m 5 "${BASE_URL}/actuator/health" 2>/dev/null || true)"
    if [[ "$body" == *'"status":"UP"'* ]] || [[ "$body" == *'"status": "UP"'* ]]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

wait_for_stack_ready() {
  if ! wait_for_compose_healthy; then
    return 1
  fi
  if ! wait_for_claim_service_http; then
    COMPOSE_PS_STATUS_REPORT="claim-service /actuator/health not UP within ${CLAIM_HTTP_READY_TIMEOUT_SEC}s"
    log_line "ERROR: ${COMPOSE_PS_STATUS_REPORT}"
    return 1
  fi
  return 0
}

claim_service_spring_profiles() {
  compose exec -T claim-service printenv SPRING_PROFILES_ACTIVE 2>/dev/null || true
}

claim_service_demo_seeder_started() {
  compose logs claim-service 2>/dev/null | grep -qF "${DEMO_SEEDER_LOG_MARKER}"
}

curl_json() {
  local method="$1" url="$2" jar="$3" body="${4:-}"
  local -a extra=(-sS -o "${PHASE0_CURL_BODY}" -w '%{http_code}' -X "$method" -b "$jar" -c "$jar")
  local code
  if [[ -n "$body" ]]; then
    extra+=(-H 'Content-Type: application/json' -d "$body")
  fi
  extra+=("$url")
  code="$(curl "${extra[@]}" 2>/dev/null)" || code=""
  code="$(normalize_http_code "$code")"
  PHASE0_LAST_HTTP_CODE="$code"
  printf '%s' "$code"
}

log_http_response_body_truncated() {
  local max=500 chunk
  if [[ ! -f "${PHASE0_CURL_BODY}" ]]; then
    log_line "response body: (none)"
    return
  fi
  chunk="$(head -c "$max" "${PHASE0_CURL_BODY}" 2>/dev/null | tr '\n' ' ')"
  log_line "response body: $(mask_secrets "$chunk")"
}

log_http_failure() {
  local label="$1" code="$2"
  log_line "${label}: HTTP ${code}"
  log_http_response_body_truncated
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

readonly CHECK_06_CLAIM_AMOUNT="1500.00"

submit_claim() {
  local jar="$1" amount="$2"
  local body code
  body="$(printf '{"policyNumber":"POL-PHASE0-%s","claimType":"AUTO","reason":"Phase0 runtime gate","description":"Automated runtime check claim","estimatedAmount":%s}' \
    "$(date +%s)" "$amount")"
  code="$(curl_json POST "${BASE_URL}/claims" "$jar" "$body")"
  [[ "$code" == "201" ]]
}

# Check 06 only: new claim under 10k, not used by checks 01–05 (submit does not touch Redis; see ClaimService.submit).
submit_claim_check_06() {
  local jar="$1"
  local body code
  body="$(printf '{"policyNumber":"POL-PHASE0-TM-A-%s","claimType":"AUTO","reason":"Phase0 TM-A cache gate","description":"Check 06 dedicated claim","estimatedAmount":%s}' \
    "$(date +%s)-${RANDOM}" "${CHECK_06_CLAIM_AMOUNT}")"
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
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "${PHASE0_CURL_BODY}" 2>/dev/null
}

claim_id_from_submit() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${PHASE0_CURL_BODY}" 2>/dev/null
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

readme_dashboard_url() {
  awk '
    /Open the secure insurance portal/ { found=1; next }
    found && /^http:\/\// { print; exit }
    found && match($0, /http:\/\/[^ ]+/) { print substr($0, RSTART, RLENGTH); exit }
  ' README.md 2>/dev/null | tr -d '`' | head -n1
}

check_01_healthy_and_single_published_port() {
  local nn="$1" name="$2"
  local not_healthy published host_ports
  not_healthy="$(unhealthy_services)"
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
  local nn="$1" name="$2"
  local nc_available=0
  if command -v nc >/dev/null 2>&1; then
    nc_available=1
  fi
  local -a services_ports=(
    'postgres:5432'
    'redis:6379'
    'kafka:9092'
    'claim-service:8081'
    'approval-service:8082'
    'settlement-service:8083'
    'notification-service:8084'
  )
  local entry svc port mapping host_port nc_notes=() fail_reasons=()
  for entry in "${services_ports[@]}"; do
    svc="${entry%%:*}"
    port="${entry##*:}"
    mapping="$(compose port "$svc" "$port" 2>/dev/null || true)"
    if [[ "$svc" == "claim-service" && "$port" == "8081" ]]; then
      if ! compose_port_is_published "$mapping"; then
        fail_reasons+=("claim-service:8081 is not published on the host (compose port: ${mapping:-<empty>})")
        continue
      fi
      host_port="${mapping##*:}"
      if [[ "$host_port" != "8081" ]]; then
        fail_reasons+=("claim-service maps to host ${host_port}, expected 8081")
      fi
      continue
    fi
    if compose_port_is_published "$mapping"; then
      fail_reasons+=("${svc}:${port} compose port returned ${mapping}")
      continue
    fi
    if [[ "$nc_available" -eq 1 ]]; then
      if nc -z -w 2 localhost "$port" >/dev/null 2>&1; then
        nc_notes+=("note: ${port} is open on the host by a non-stack process (compose port empty)")
      fi
    fi
  done
  if ((${#fail_reasons[@]} > 0)); then
    emit_result "$nn" FAIL "$name" "${fail_reasons[0]}"
    return
  fi
  local http_code
  http_code="$(curl_http_code "${BASE_URL}/actuator/health")"
  if [[ "$http_code" == "000" ]]; then
    emit_result "$nn" FAIL "$name" "compose port OK but claim-service HTTP on 8081 not reachable"
    return
  fi
  local evidence="compose port: only claim-service:8081 published; HTTP 8081 reachable (actuator ${http_code})"
  if ((${#nc_notes[@]} > 0)); then
    evidence="${evidence}; ${nc_notes[*]}"
  elif [[ "$nc_available" -eq 0 ]]; then
    evidence="${evidence}; nc unavailable for secondary probe"
  fi
  emit_result "$nn" PASS "$name" "$evidence"
}

check_03_redis_noauth_without_password() {
  local nn="$1" name="$2"
  local out
  out="$(compose exec -T redis redis-cli ping 2>&1 || true)"
  if [[ "$out" == *NOAUTH* ]]; then
    emit_result "$nn" PASS "$name" "redis-cli ping returned NOAUTH"
  else
    emit_result "$nn" FAIL "$name" "expected NOAUTH, got: ${out//$'\n'/ }"
  fi
}

check_04_flyway_and_empty_portal_without_demo() {
  local nn="$1" name="$2"
  local flyway_count portal_count logs_ok=false

  compose down -v >/dev/null 2>&1 || true
  if ! compose_with_profile "" up -d --build >/dev/null 2>&1; then
    emit_result "$nn" FAIL "$name" "compose up without demo profile failed"
    return
  fi
  if ! wait_for_stack_ready; then
    emit_result "$nn" FAIL "$name" "non-demo stack not ready: ${COMPOSE_PS_STATUS_REPORT}"
    return
  fi
  local active_profiles
  active_profiles="$(claim_service_spring_profiles)"
  if [[ "$active_profiles" == *demo* ]]; then
    emit_result "$nn" FAIL "$name" "expected non-demo stack but claim-service SPRING_PROFILES_ACTIVE=${active_profiles:-<empty>}"
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
    emit_result "$nn" PASS "$name" "non-demo stack; flyway_schema_history_claim_service has ${flyway_count} successful migration(s); portal_users count=0"
    return
  fi
  if [[ "$logs_ok" == true ]] && [[ "$portal_count" == "0" ]]; then
    emit_result "$nn" PASS "$name" "non-demo stack; claim-service Flyway logs OK; portal_users count=0"
    return
  fi
  emit_result "$nn" FAIL "$name" "flyway_rows=${flyway_count} portal_users=${portal_count} flyway_logs=${logs_ok}"
}

check_05_demo_sign_in_after_fresh_volume() {
  local nn="$1" name="$2" jar
  jar="$(phase0_mktemp)"

  if ! demo_profile_enabled; then
    emit_result "$nn" SKIPPED "$name" "SPRING_PROFILES_ACTIVE in .env does not include demo"
    return
  fi
  compose down -v >/dev/null 2>&1 || true
  if ! compose_with_profile "demo" up -d --build >/dev/null 2>&1; then
    emit_result "$nn" FAIL "$name" "compose up with demo profile failed"
    return
  fi
  if ! wait_for_stack_ready; then
    emit_result "$nn" FAIL "$name" "demo stack not ready: ${COMPOSE_PS_STATUS_REPORT}"
    return
  fi
  local active_profiles seeder_found=false
  active_profiles="$(claim_service_spring_profiles)"
  if claim_service_demo_seeder_started; then
    seeder_found=true
  fi
  log_line "[05] claim-service SPRING_PROFILES_ACTIVE=${active_profiles:-<empty>}"
  log_line "[05] DemoProfileSeeder log line found=${seeder_found}"
  if [[ "$active_profiles" != *demo* ]]; then
    emit_result "$nn" FAIL "$name" "claim-service SPRING_PROFILES_ACTIVE missing demo (got: ${active_profiles:-<empty>})"
    return
  fi
  if [[ "$seeder_found" != true ]]; then
    emit_result "$nn" FAIL "$name" "claim-service logs missing DemoProfileSeeder line (${DEMO_SEEDER_LOG_MARKER}…)"
    return
  fi
  if login_user "agent@insureflow.local" "${DEMO_AGENT_PASSWORD}" "$jar"; then
    emit_result "$nn" PASS "$name" "agent@insureflow.local login HTTP 200 after down -v && up"
  else
    log_http_failure "[05] agent@insureflow.local login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "agent@insureflow.local login failed after fresh volume (HTTP ${PHASE0_LAST_HTTP_CODE})"
  fi
}

check_06_live_cache_authorization_tm_a() {
  local nn="$1" name="$2"
  local jar_a jar_b jar_broker jar_agent claim_id code status

  if ! demo_profile_enabled; then
    emit_result "$nn" SKIPPED "$name" "demo profile required for seeded AGENT and BROKER users"
    return
  fi
  jar_a="$(phase0_mktemp)"
  jar_b="$(phase0_mktemp)"
  jar_broker="$(phase0_mktemp)"
  jar_agent="$(phase0_mktemp)"

  if ! register_insured "tm-a-insured-phase0-$(date +%s)@example.com" "${DEMO_INSURED_PASSWORD}" "TM-A Insured A" "$jar_a"; then
    log_http_failure "[06] INSURED A registration" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "INSURED A registration for check 06 failed (HTTP ${PHASE0_LAST_HTTP_CODE})"
    return
  fi
  if ! submit_claim_check_06 "$jar_a"; then
    emit_result "$nn" FAIL "$name" "INSURED A claim submit failed (${CHECK_06_CLAIM_AMOUNT} under 10k authority)"
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
  log_check_06_redis_scan "before-read"
  if redis_scan_contains_claim_id "$claim_id"; then
    emit_result "$nn" FAIL "$name" "before-read: claims* key for claim already present before AGENT read"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_agent")"
  if [[ "$code" != "200" ]]; then
    emit_result "$nn" FAIL "$name" "AGENT warm-cache GET returned HTTP ${code}"
    return
  fi
  log_check_06_redis_scan "after-read"
  if ! redis_scan_contains_claim_id "$claim_id"; then
    emit_result "$nn" FAIL "$name" "after-read: claims* key for claim missing after AGENT read (Redis cache not in use)"
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
  log_check_06_redis_scan "after-decide"
  if redis_scan_contains_claim_id "$claim_id"; then
    emit_result "$nn" FAIL "$name" "after-decide: claims* key for claim still present after decide (scan must run before AGENT re-read)"
    return
  fi
  status="$(claim_status "$jar_agent" "$claim_id" || true)"
  if [[ "$status" != "APPROVED" ]]; then
    emit_result "$nn" FAIL "$name" "after-reread: AGENT GET status=${status:-unknown}, expected APPROVED"
    return
  fi
  log_check_06_redis_scan "after-reread"
  if ! redis_scan_contains_claim_id "$claim_id"; then
    emit_result "$nn" FAIL "$name" "after-reread: claims* key for claim missing after AGENT GET (CachedClaimReader should repopulate cache)"
    return
  fi
  emit_result "$nn" PASS "$name" "INSURED B and BROKER HTTP 404; claim ${claim_id} Redis absent before read, present after read, absent after decide, present after re-read; status APPROVED (${CHECK_06_CLAIM_AMOUNT})"
}

check_07_tm_b_kafka_integration() {
  local nn="$1" name="$2"
  if has_tm_b_kafka_integration_test; then
    emit_result "$nn" SKIPPED "$name" "Kafka TM-B test exists but isn't wired into this script"
    return
  fi
  emit_result "$nn" SKIPPED "$name" "no Kafka integration test for TM-B (unit-only WorkflowNotificationListenerLogLeakTest on main)"
}

check_12_readme_dashboard_url() {
  local nn="$1" name="$2"
  local url code
  url="$(readme_dashboard_url)"
  if [[ -z "$url" ]]; then
    emit_result "$nn" FAIL "$name" "could not parse dashboard URL from README.md"
    return
  fi
  code="$(curl_http_code "$url")"
  if [[ "$code" == "200" ]]; then
    emit_result "$nn" PASS "$name" "GET ${url} returned HTTP 200"
  else
    emit_result "$nn" FAIL "$name" "GET ${url} returned HTTP ${code}"
  fi
}

check_13_tm_d_high_value_manual_decide() {
  local nn="$1" name="$2"
  local jar_insured jar_agent claim_id status code

  if ! demo_profile_enabled; then
    emit_result "$nn" SKIPPED "$name" "demo profile required for legacy AGENT user"
    return
  fi
  jar_insured="$(phase0_mktemp)"
  jar_agent="$(phase0_mktemp)"

  if ! login_user "insured@insureflow.local" "${DEMO_INSURED_PASSWORD}" "$jar_insured"; then
    log_http_failure "[13] insured@insureflow.local login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "insured demo login failed (HTTP ${PHASE0_LAST_HTTP_CODE})"
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
    log_http_failure "[13] agent@insureflow.local login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "legacy AGENT login failed (HTTP ${PHASE0_LAST_HTTP_CODE})"
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

validate_checks_array_functions() {
  local spec fn missing=()
  for spec in "${CHECKS[@]}"; do
    IFS='|' read -r _ _ fn _ <<< "$spec"
    [[ "$fn" == "." ]] && continue
    if ! declare -f "$fn" >/dev/null 2>&1; then
      missing+=("$fn")
    fi
  done
  if ((${#missing[@]} > 0)); then
    printf 'ERROR: missing check function(s) for CHECKS array: %s\n' "${missing[*]}" >&2
    exit 2
  fi
}

run_all_checks() {
  local spec nn display fn stub_reason
  for spec in "${CHECKS[@]}"; do
    IFS='|' read -r nn display fn stub_reason <<< "$spec"
    if [[ "$fn" == "." ]]; then
      emit_result "$nn" SKIPPED "$display" "$stub_reason"
      continue
    fi
    set +e
    "$fn" "$nn" "$display"
    set -e
  done
}

print_summary() {
  log_line "SUMMARY pass=${PASS_COUNT} fail=${FAIL_COUNT} skipped=${SKIP_COUNT} total=${#CHECKS[@]}"
}

list_checks() {
  local spec nn display _fn _stub
  for spec in "${CHECKS[@]}"; do
    IFS='|' read -r nn display _fn _stub <<< "$spec"
    printf '%s\t%s\n' "$nn" "$display"
  done
}

main() {
  require_compose_project_guard
  require_env_file
  load_env_from_dotenv
  require_demo_env_vars_when_enabled
  validate_checks_array_functions

  PHASE0_CURL_BODY="$(phase0_mktemp)"
  trap phase0_cleanup_tmps EXIT

  : > "$LOG_FILE"
  log_line "phase0-runtime-check starting at ${TIMESTAMP}"
  log_repo_git_state
  log_line "repo=${REPO_ROOT} COMPOSE_PROJECT=${COMPOSE_PROJECT}"

  log_line "compose down -v (throwaway stack)"
  compose down -v

  log_line "compose up -d --build"
  compose up -d --build

  if ! wait_for_stack_ready; then
    log_line "WARN: initial stack readiness timed out (health and/or claim-service HTTP); running checks anyway"
  fi

  run_all_checks
  print_summary

  if [[ "$FAIL_COUNT" -eq 0 && "$SKIP_COUNT" -eq 0 && "$PASS_COUNT" -eq ${#CHECKS[@]} ]]; then
    exit 0
  fi
  exit 1
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [[ "${1:-}" == "--list" ]]; then
    list_checks
    exit 0
  fi
  main "$@"
fi
