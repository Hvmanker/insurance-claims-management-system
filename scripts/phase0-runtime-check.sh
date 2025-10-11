#!/usr/bin/env bash
# Phase 0 runtime gate — throwaway Compose project "phase0check" only.
# shellcheck disable=SC2317,SC2329,SC2016,SC2237 # CHECKS entries are literal names; check_* functions run via CHECKS dispatch; demo guard uses [ -n "${!var:-}" ]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

readonly REQUIRED_COMPOSE_PROJECT="phase0check"
readonly HEALTH_TIMEOUT_SEC="${PHASE0_HEALTH_TIMEOUT_SEC:-900}"
readonly HTTP_TIMEOUT_SEC="${PHASE0_HTTP_TIMEOUT_SEC:-180}"
readonly BASE_URL="${PHASE0_BASE_URL:-http://localhost:8081}"
readonly DEMO_SEEDER_LOG_MARKER='Demo portal user ready:'
declare -a DEMO_PASSWORD_ENV_NAMES=()

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
HTTP_WAIT_STATUS_REPORT=""
PHASE0_ACTIVE_COMPOSE_PROJECT=""
declare -a PHASE0_STOPPED_PROJECTS=()
declare -a PHASE0_USED_PROJECTS=()
declare -a PHASE0_CREATED_PROJECTS=()
PHASE0_COMPOSE_RUN_TS=""
PHASE0_PROJECT_STARTUP=""
PHASE0_PROJECT_C04=""
PHASE0_PROJECT_C05=""
PHASE0_RESOLVED_COMPOSE_BIN=""
phase0_compose_use_standalone=0

phase0_no_teardown_enabled() {
  [[ "${PHASE0_NO_TEARDOWN:-}" == "1" ]]
}

# COMPOSE_PROJECT: exactly phase0check, or phase0check-<suffix> only with PHASE0_NO_TEARDOWN=1.
phase0_compose_project_name_valid() {
  local cp="$1"
  [[ "$cp" =~ ^phase0check(-[0-9a-z_-]+)?$ ]] || return 1
  if [[ "$cp" == "${REQUIRED_COMPOSE_PROJECT}" ]]; then
    return 0
  fi
  phase0_no_teardown_enabled
}

# Per-phase compose project names (lowercase); one or more -suffix segments after phase0check.
phase0_runtime_compose_project_name_valid() {
  local name="$1"
  [[ "$name" =~ ^phase0check(-[0-9a-z_-]+)+$ ]]
}

phase0_compose_config_env_file() {
  if [[ -n "${PHASE0_COMPOSE_CONFIG_ENV_FILE:-}" ]]; then
    printf '%s' "${PHASE0_COMPOSE_CONFIG_ENV_FILE}"
  else
    printf '%s' "${REPO_ROOT}/.env"
  fi
}

phase0_fixed_compose_search_path() {
  printf '%s' "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
}

phase0_compose_cli_sanity_check() {
  local bin="$1" out
  [[ -n "$bin" && -x "$bin" ]] || return 1
  if [[ "$(basename "$bin")" == "docker-compose" ]]; then
    out="$("$bin" version 2>&1)" || return 1
  else
    out="$("$bin" compose version 2>&1)" || return 1
  fi
  [[ "$out" =~ [0-9] ]]
}

phase0_try_compose_candidate() {
  local bin="$1"
  if [[ -z "$bin" || ! -x "$bin" ]]; then
    return 1
  fi
  if ! phase0_compose_cli_sanity_check "$bin"; then
    return 1
  fi
  if [[ "$(basename "$bin")" == "docker-compose" ]]; then
    PHASE0_RESOLVED_COMPOSE_BIN="$bin"
    phase0_compose_use_standalone=1
    return 0
  fi
  PHASE0_RESOLVED_COMPOSE_BIN="$bin"
  phase0_compose_use_standalone=0
  return 0
}

# Resolve docker compose CLI: PHASE0_DOCKER_BIN, then ORIGINAL PATH docker / docker-compose, then fixed dirs.
phase0_resolve_compose_executable() {
  local search_path="${PHASE0_ORIGINAL_PATH:-$PATH}"
  local fixed_path bin

  PHASE0_RESOLVED_COMPOSE_BIN=""
  phase0_compose_use_standalone=0

  if [[ -n "${PHASE0_DOCKER_BIN:-}" && -x "${PHASE0_DOCKER_BIN}" ]]; then
    if phase0_try_compose_candidate "${PHASE0_DOCKER_BIN}"; then
      return 0
    fi
  fi

  bin="$(PATH="${search_path}" command -v docker 2>/dev/null || true)"
  if phase0_try_compose_candidate "$bin"; then
    return 0
  fi

  bin="$(PATH="${search_path}" command -v docker-compose 2>/dev/null || true)"
  if phase0_try_compose_candidate "$bin"; then
    return 0
  fi

  fixed_path="$(phase0_fixed_compose_search_path)"
  bin="$(PATH="${fixed_path}" command -v docker 2>/dev/null || true)"
  if phase0_try_compose_candidate "$bin"; then
    return 0
  fi

  bin="$(PATH="${fixed_path}" command -v docker-compose 2>/dev/null || true)"
  if phase0_try_compose_candidate "$bin"; then
    return 0
  fi

  return 1
}

phase0_run_compose_config_q() {
  local project="$1" env_file="${2:-$(phase0_compose_config_env_file)}"
  if [[ -z "${PHASE0_RESOLVED_COMPOSE_BIN}" ]] && ! phase0_resolve_compose_executable; then
    return 127
  fi
  if [[ "${phase0_compose_use_standalone}" -eq 1 ]]; then
    "${PHASE0_RESOLVED_COMPOSE_BIN}" -p "$project" --env-file "$env_file" config -q
  else
    "${PHASE0_RESOLVED_COMPOSE_BIN}" compose -p "$project" --env-file "$env_file" config -q
  fi
}

phase0_validate_suffixed_compose_project_name() {
  local name="$1" err rc
  if [[ "$name" == "${REQUIRED_COMPOSE_PROJECT}" ]]; then
    return 0
  fi
  if ! phase0_runtime_compose_project_name_valid "$name"; then
    printf 'compose project name %q does not match ^phase0check(-[0-9a-z_-]+)+$\n' "$name" >&2
    return 2
  fi
  if ! phase0_resolve_compose_executable; then
    printf 'compose rejected project name: docker compose CLI not found\n' >&2
    return 2
  fi
  set +e
  err="$(phase0_run_compose_config_q "$name" "$(phase0_compose_config_env_file)" 2>&1)"
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf 'compose rejected project name: %s\n' "$err" >&2
    return 2
  fi
  return 0
}

# Compose config -q only (no regex); for asserting compose rejects invalid project names.
phase0_validate_compose_project_name_compose_only() {
  local name="$1" err rc
  if [[ "$name" == "${REQUIRED_COMPOSE_PROJECT}" ]]; then
    return 0
  fi
  if ! phase0_resolve_compose_executable; then
    printf 'compose rejected project name: docker compose CLI not found\n' >&2
    return 2
  fi
  set +e
  err="$(phase0_run_compose_config_q "$name" "$(phase0_compose_config_env_file)" 2>&1)"
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf 'compose rejected project name: %s\n' "$err" >&2
    return 2
  fi
  return 0
}

phase0_assert_compose_project_accepted_by_cli() {
  phase0_validate_suffixed_compose_project_name "$1" || exit $?
}

phase0_init_and_validate_no_teardown_projects() {
  local n
  if ! phase0_no_teardown_enabled; then
    return 0
  fi
  PHASE0_COMPOSE_RUN_TS="$(date -u +%Y%m%dt%H%M%Sz)"
  PHASE0_PROJECT_STARTUP="$(phase0_compose_project_for_phase startup)"
  PHASE0_PROJECT_C04="$(phase0_compose_project_for_phase c04)"
  PHASE0_PROJECT_C05="$(phase0_compose_project_for_phase c05)"
  for n in "${PHASE0_PROJECT_STARTUP}" "${PHASE0_PROJECT_C04}" "${PHASE0_PROJECT_C05}"; do
    phase0_assert_compose_project_accepted_by_cli "$n"
  done
  log_line "PHASE0_NO_TEARDOWN: validated compose projects (ts=${PHASE0_COMPOSE_RUN_TS}) startup=${PHASE0_PROJECT_STARTUP} c04=${PHASE0_PROJECT_C04} c05=${PHASE0_PROJECT_C05}"
}

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
  if phase0_no_teardown_enabled; then
    if ((${#PHASE0_TMP_FILES[@]} > 0)); then
      log_line "PHASE0_NO_TEARDOWN: leaving temporary files on disk:"
      for f in ${PHASE0_TMP_FILES[@]+"${PHASE0_TMP_FILES[@]}"}; do
        log_line "  ${f}"
      done
    fi
    return 0
  fi
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
  local cp="${COMPOSE_PROJECT:-}"
  if phase0_compose_project_name_valid "$cp"; then
    return 0
  fi
  if phase0_no_teardown_enabled; then
    printf 'COMPOSE_PROJECT must match ^phase0check(-[0-9a-z_-]+)?$ (got %q); refusing to run.\n' \
      "${cp:-<unset>}" >&2
  else
    printf 'COMPOSE_PROJECT must be exactly %q (got %q); refusing to run.\n' \
      "$REQUIRED_COMPOSE_PROJECT" "${cp:-<unset>}" >&2
  fi
  exit 2
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

persona_fixtures_dir() {
  if [[ -n "${PHASE0_PERSONA_FIXTURES_DIR:-}" ]]; then
    printf '%s' "${PHASE0_PERSONA_FIXTURES_DIR}"
  else
    printf '%s' "${REPO_ROOT}/claim-service/src/main/resources/fixtures/personas"
  fi
}

# Bash 3.2–safe: discover DEMO_* password env names from top-level persona JSON only.
collect_demo_password_env_names_from_fixtures() {
  local dir="$1" f var
  DEMO_PASSWORD_ENV_NAMES=()
  if [[ ! -d "$dir" ]]; then
    printf 'Missing demo persona fixtures directory: %s\n' "$dir" >&2
    return 2
  fi
  while IFS= read -r var; do
    [[ -z "$var" ]] && continue
    DEMO_PASSWORD_ENV_NAMES+=("$var")
  done < <(
    for f in "${dir}"/*.json; do
      [[ -f "$f" ]] || continue
      grep '"passwordEnv"' "$f" 2>/dev/null || true
    done | sed -En 's/.*"(DEMO_[A-Z_]+_PASSWORD)".*/\1/p' | sort -u
  )
  if ((${#DEMO_PASSWORD_ENV_NAMES[@]} == 0)); then
    printf 'No DEMO_* password env names found in persona fixtures under %s\n' "$dir" >&2
    return 2
  fi
  return 0
}

require_demo_env_vars_when_enabled() {
  local var missing=()
  if ! demo_profile_enabled; then
    return 0
  fi
  collect_demo_password_env_names_from_fixtures "$(persona_fixtures_dir)" || exit $?
  for var in "${DEMO_PASSWORD_ENV_NAMES[@]}"; do
    if ! [ -n "${!var:-}" ]; then
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
  if phase0_no_teardown_enabled; then
    docker compose -p "${PHASE0_ACTIVE_COMPOSE_PROJECT}" --env-file "${REPO_ROOT}/.env" "$@"
  else
    docker compose -p "${COMPOSE_PROJECT}" --env-file "${REPO_ROOT}/.env" "$@"
  fi
}

phase0_compose_project_for_phase() {
  local phase="$1" ts="${PHASE0_COMPOSE_RUN_TS:-}"
  if [[ -z "$ts" ]]; then
    ts="$(date -u +%Y%m%dt%H%M%Sz)"
  fi
  printf '%s' "${COMPOSE_PROJECT}-${ts}-${phase}"
}

phase0_list_volumes_for_project() {
  local project="$1"
  docker volume ls -q --filter "label=com.docker.compose.project=${project}" 2>/dev/null || true
}

phase0_log_phase_compose_header() {
  local phase_label="$1" vol
  local -a vols=()
  log_line "compose phase ${phase_label}: active project=${PHASE0_ACTIVE_COMPOSE_PROJECT}"
  log_line "  volumes (label com.docker.compose.project=${PHASE0_ACTIVE_COMPOSE_PROJECT}):"
  while IFS= read -r vol; do
    [[ -n "$vol" ]] && vols+=("$vol")
  done < <(phase0_list_volumes_for_project "${PHASE0_ACTIVE_COMPOSE_PROJECT}")
  if ((${#vols[@]} == 0)); then
    log_line "    (none yet)"
  else
    for vol in "${vols[@]}"; do
      log_line "    ${vol}"
    done
  fi
}

phase0_compose_stop_is_allowed() {
  local project="$1" p
  if [[ "$project" == "${REQUIRED_COMPOSE_PROJECT}" ]]; then
    return 0
  fi
  for p in ${PHASE0_CREATED_PROJECTS[@]+"${PHASE0_CREATED_PROJECTS[@]}"}; do
    [[ "$p" == "$project" ]] && return 0
  done
  return 1
}

phase0_compose_stop_project() {
  local project="$1"
  if ! phase0_compose_stop_is_allowed "$project"; then
    return 0
  fi
  log_line "compose stop (project=${project})"
  docker compose -p "$project" --env-file "${REPO_ROOT}/.env" stop >/dev/null 2>&1 || true
  PHASE0_STOPPED_PROJECTS+=("$project")
}

phase0_switch_compose_project() {
  local new_project="$1" prev="${PHASE0_ACTIVE_COMPOSE_PROJECT}"
  if [[ -n "$prev" && "$prev" != "$new_project" ]]; then
    phase0_compose_stop_project "$prev"
  fi
  PHASE0_ACTIVE_COMPOSE_PROJECT="$new_project"
  PHASE0_CREATED_PROJECTS+=("$new_project")
  PHASE0_USED_PROJECTS+=("$new_project")
  log_line "compose project active: ${PHASE0_ACTIVE_COMPOSE_PROJECT}"
}

phase0_prepare_fresh_compose_stack() {
  local phase="$1" target=""
  if phase0_no_teardown_enabled; then
    case "$phase" in
      c04) target="${PHASE0_PROJECT_C04}" ;;
      c05) target="${PHASE0_PROJECT_C05}" ;;
      *)
        printf 'unknown no-teardown compose phase %q\n' "$phase" >&2
        exit 2
        ;;
    esac
    phase0_switch_compose_project "$target"
    phase0_log_phase_compose_header "$phase"
  else
    compose down -v >/dev/null 2>&1 || true
  fi
}

phase0_log_no_teardown_closing_note() {
  local f p vol last="${PHASE0_ACTIVE_COMPOSE_PROJECT}"
  log_line "PHASE0_NO_TEARDOWN closing note:"
  log_line "  stack left running: project=${last}"
  log_line "  claim-service logs: docker compose -p ${last} logs claim-service"
  if ((${#PHASE0_STOPPED_PROJECTS[@]} > 0)); then
    log_line "  stopped compose projects: ${PHASE0_STOPPED_PROJECTS[*]}"
  else
    log_line "  stopped compose projects: (none recorded)"
  fi
  if ((${#PHASE0_TMP_FILES[@]} > 0)); then
    log_line "  temporary files left on disk:"
    for f in ${PHASE0_TMP_FILES[@]+"${PHASE0_TMP_FILES[@]}"}; do
      log_line "    ${f}"
    done
  fi
  for p in ${PHASE0_CREATED_PROJECTS[@]+"${PHASE0_CREATED_PROJECTS[@]}"}; do
    log_line "  volumes for project=${p}:"
    while IFS= read -r vol; do
      [[ -n "$vol" ]] && log_line "    ${vol}"
    done < <(phase0_list_volumes_for_project "$p")
  done
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

services_not_fully_healthy_list() {
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

# Full compose ps -a snapshot (every service state and health) for timeout diagnostics.
unhealthy_services() {
  local svc state health
  local -a parts=()
  while IFS=$'\t' read -r svc state health; do
    [[ -z "$svc" ]] && continue
    parts+=("${svc}(state=${state},health=${health:-empty})")
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
  COMPOSE_PS_STATUS_REPORT="$(unhealthy_services)"
  log_line "ERROR: compose health wait timed out after ${HEALTH_TIMEOUT_SEC}s"
  log_line "unhealthy_services: ${COMPOSE_PS_STATUS_REPORT}"
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
  local deadline=$((SECONDS + HTTP_TIMEOUT_SEC))
  local body code chunk tmp_body
  HTTP_WAIT_STATUS_REPORT=""
  tmp_body="$(phase0_mktemp)"
  while (( SECONDS < deadline )); do
    code="$(curl -sS -m 5 -o "$tmp_body" -w '%{http_code}' "${BASE_URL}/actuator/health" 2>/dev/null)" || code=""
    code="$(normalize_http_code "$code")"
    PHASE0_LAST_HTTP_CODE="$code"
    body="$(cat "$tmp_body" 2>/dev/null || true)"
    if [[ "$body" == *'"status":"UP"'* ]] || [[ "$body" == *'"status": "UP"'* ]]; then
      return 0
    fi
    sleep 3
  done
  body="$(cat "$tmp_body" 2>/dev/null || true)"
  chunk="$(printf '%s' "$body" | head -c 200 | tr '\n' ' ')"
  HTTP_WAIT_STATUS_REPORT="actuator/health not UP within ${HTTP_TIMEOUT_SEC}s; last HTTP ${PHASE0_LAST_HTTP_CODE}; body: $(mask_secrets "$chunk")"
  log_line "ERROR: ${HTTP_WAIT_STATUS_REPORT}"
  return 1
}

wait_for_stack_ready() {
  HTTP_WAIT_STATUS_REPORT=""
  if ! wait_for_compose_healthy; then
    return 1
  fi
  if ! wait_for_claim_service_http; then
    COMPOSE_PS_STATUS_REPORT="${HTTP_WAIT_STATUS_REPORT}"
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

curl_response_body_snippet() {
  local max="${1:-500}" chunk
  if [[ ! -f "${PHASE0_CURL_BODY}" ]]; then
    printf '%s' "(none)"
    return
  fi
  chunk="$(head -c "$max" "${PHASE0_CURL_BODY}" 2>/dev/null | tr '\n' ' ')"
  mask_secrets "$chunk"
}

http_failure_appendix() {
  local code="$1"
  printf 'HTTP %s; body: %s' "$code" "$(curl_response_body_snippet 500)"
}

log_http_response_body_truncated() {
  log_line "response body: $(curl_response_body_snippet 500)"
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
    "$(date +%s)-$$-${RANDOM}" "${CHECK_06_CLAIM_AMOUNT}")"
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
  not_healthy="$(services_not_fully_healthy_list)"
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

  phase0_prepare_fresh_compose_stack "c04"
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
  phase0_prepare_fresh_compose_stack "c05"
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
    if phase0_no_teardown_enabled; then
      emit_result "$nn" PASS "$name" "agent@insureflow.local login HTTP 200 on fresh project ${PHASE0_ACTIVE_COMPOSE_PROJECT}"
    else
      emit_result "$nn" PASS "$name" "agent@insureflow.local login HTTP 200 after down -v && up"
    fi
  else
    log_http_failure "[05] agent@insureflow.local login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "agent@insureflow.local login failed after fresh volume ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
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
    emit_result "$nn" FAIL "$name" "INSURED A registration for check 06 failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
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
    log_http_failure "[06] AGENT login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "AGENT login failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
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
    log_http_failure "[06] INSURED B registration" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "INSURED B registration failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_b")"
  if [[ "$code" != "404" ]]; then
    emit_result "$nn" FAIL "$name" "INSURED B GET after cache warm returned HTTP ${code}, expected 404"
    return
  fi

  if ! login_user "broker@insureflow.local" "${DEMO_BROKER_PASSWORD}" "$jar_broker"; then
    log_http_failure "[06] BROKER login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "BROKER login failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
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
    emit_result "$nn" FAIL "$name" "insured demo login failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
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
    emit_result "$nn" FAIL "$name" "legacy AGENT login failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
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

  if phase0_no_teardown_enabled; then
    PHASE0_ACTIVE_COMPOSE_PROJECT=""
    log_line "PHASE0_NO_TEARDOWN=1: per-phase compose projects; skipping down/rm teardown"
    phase0_init_and_validate_no_teardown_projects
    phase0_compose_stop_project "${REQUIRED_COMPOSE_PROJECT}"
    phase0_switch_compose_project "${PHASE0_PROJECT_STARTUP}"
    phase0_log_phase_compose_header "startup"
    log_line "compose up -d --build (project=${PHASE0_ACTIVE_COMPOSE_PROJECT})"
  else
    log_line "compose down -v (throwaway stack)"
    compose down -v
    log_line "compose up -d --build"
  fi
  compose up -d --build

  if ! wait_for_stack_ready; then
    log_line "WARN: initial stack readiness timed out (health and/or claim-service HTTP); running checks anyway"
  fi

  run_all_checks
  print_summary
  if phase0_no_teardown_enabled; then
    phase0_log_no_teardown_closing_note
  fi

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
