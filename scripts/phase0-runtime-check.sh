#!/usr/bin/env bash
# Phase 0 runtime gate — throwaway Compose project "phase0check" only.
# shellcheck disable=SC2317,SC2329,SC2016,SC2237 # CHECKS entries are literal names; check_* functions run via CHECKS dispatch; demo guard uses [ -n "${!var:-}" ]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

readonly REQUIRED_COMPOSE_PROJECT="phase0check"
readonly HEALTH_TIMEOUT_SEC="${PHASE0_HEALTH_TIMEOUT_SEC:-900}"
readonly HTTP_TIMEOUT_SEC="${PHASE0_HTTP_TIMEOUT_SEC:-180}"
readonly CHECK_05_READINESS_TIMEOUT_SEC="${PHASE0_CHECK_05_READINESS_TIMEOUT_SEC:-180}"
readonly CHECK_05_DEMO_SEEDER_TIMEOUT_SEC="${PHASE0_CHECK_05_DEMO_SEEDER_TIMEOUT_SEC:-300}"
readonly DEMO_SEEDER_EXPECTED_COUNT=9
readonly BASE_URL="${PHASE0_BASE_URL:-http://localhost:8081}"
readonly DEMO_SEEDER_LOG_MARKER='Demo portal user ready:'
declare -a DEMO_PASSWORD_ENV_NAMES=()

# Phase 0 gate: exactly three checks on one fresh demo-profile stack (P1 → P2 → P3).
# Format: id|display name|handler
CHECKS=(
  'P1|Startup burst: 12 claims at 1000.00 reach APPROVED or SETTLED within 60s of each submit|check_P1_startup_burst'
  'P2|Stack healthy, locked down, Flyway on claim-service, and Kafka topics pre-created|check_P2_stack_healthy_locked_topics'
  'P3|Demo login, 15000.00 over-10k limit, Redis cache isolation, and AGENT decide|check_P3_login_cache_high_value'
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
PHASE0_REGISTER_HTTP_CODE=""
COMPOSE_PS_STATUS_REPORT=""
PHASE0_KAFKA_INIT_FAIL_REASON=""
HTTP_WAIT_STATUS_REPORT=""
PHASE0_ACTIVE_COMPOSE_PROJECT=""
declare -a PHASE0_STOPPED_PROJECTS=()
declare -a PHASE0_USED_PROJECTS=()
declare -a PHASE0_CREATED_PROJECTS=()
PHASE0_COMPOSE_RUN_TS=""
PHASE0_PROJECT_STARTUP=""
PHASE0_RESOLVED_COMPOSE_BIN=""
phase0_compose_use_standalone=0

phase0_no_teardown_enabled() {
  [[ "${PHASE0_NO_TEARDOWN:-}" == "1" ]]
}

phase0_bash_errexit_enabled() {
  [[ $- == *e* ]]
}

phase0_bash_errexit_off_if_on() {
  if phase0_bash_errexit_enabled; then
    set +e
    return 0
  fi
  return 1
}

phase0_bash_errexit_restore_if_was_on() {
  local was_on="$1"
  if [[ "$was_on" == "1" ]]; then
    set -e
  fi
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
    printf '%s' "${REPO_ROOT}/.env.example"
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
  local name="$1" err errexit_was=0 rc=0
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
  if phase0_bash_errexit_off_if_on; then
    errexit_was=1
  fi
  err="$(phase0_run_compose_config_q "$name" "$(phase0_compose_config_env_file)" 2>&1)" || rc=$?
  phase0_bash_errexit_restore_if_was_on "$errexit_was"
  if [[ "$rc" -ne 0 ]]; then
    printf 'compose rejected project name: %s\n' "$err" >&2
    return 2
  fi
  return 0
}

# Compose config -q only (no regex); for asserting compose rejects invalid project names.
phase0_validate_compose_project_name_compose_only() {
  local name="$1" err errexit_was=0 rc=0
  if [[ "$name" == "${REQUIRED_COMPOSE_PROJECT}" ]]; then
    return 0
  fi
  if ! phase0_resolve_compose_executable; then
    printf 'compose rejected project name: docker compose CLI not found\n' >&2
    return 2
  fi
  if phase0_bash_errexit_off_if_on; then
    errexit_was=1
  fi
  err="$(phase0_run_compose_config_q "$name" "$(phase0_compose_config_env_file)" 2>&1)" || rc=$?
  phase0_bash_errexit_restore_if_was_on "$errexit_was"
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
  phase0_assert_compose_project_accepted_by_cli "${PHASE0_PROJECT_STARTUP}"
  log_line "PHASE0_NO_TEARDOWN: validated compose project (ts=${PHASE0_COMPOSE_RUN_TS}) startup=${PHASE0_PROJECT_STARTUP}"
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

phase0_log_no_teardown_closing_note() {
  local f p vol last="${PHASE0_ACTIVE_COMPOSE_PROJECT}"
  log_line "PHASE0_NO_TEARDOWN closing note:"
  log_line "  stack left running: project=${last}"
  log_line "  claim-service logs: docker compose -p ${last} logs claim-service"
  if ((${#PHASE0_STOPPED_PROJECTS[@]} > 0)); then
    log_line "  stopped compose projects: ${PHASE0_STOPPED_PROJECTS[*]+"${PHASE0_STOPPED_PROJECTS[*]}"}"
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

phase0_stamp_field() {
  local key="$1" stamp_file="${REPO_ROOT}/STAMP.txt" line="" value="" alt_key=""
  [[ "$key" == "commit" ]] && alt_key="tip"
  if [[ -f "$stamp_file" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ "$line" =~ ^[[:space:]]*${key}[[:space:]]*=[[:space:]]*(.+)$ ]] && value="${BASH_REMATCH[1]}"
      [[ "$line" =~ ^[[:space:]]*${key}[[:space:]]+(.+)$ ]] && value="${BASH_REMATCH[1]}"
      if [[ -n "$alt_key" ]]; then
        [[ "$line" =~ ^[[:space:]]*${alt_key}[[:space:]]*=[[:space:]]*(.+)$ ]] && value="${BASH_REMATCH[1]}"
        [[ "$line" =~ ^[[:space:]]*${alt_key}[[:space:]]+(.+)$ ]] && value="${BASH_REMATCH[1]}"
      fi
    done < "$stamp_file"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ -n "$value" ]]; then
      printf '%s' "$value"
    fi
  fi
  return 0
}

log_repo_git_state() {
  local head tree tarball_sha256 porcelain stamp_commit stamp_tree stamp_tarball
  if [[ -d "${REPO_ROOT}/.git" ]]; then
    head="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    tree="$(git -C "$REPO_ROOT" rev-parse 'HEAD^{tree}' 2>/dev/null || echo unknown)"
    log_line "git rev-parse HEAD: ${head}"
    log_line "git rev-parse HEAD^{tree}: ${tree}"
    porcelain="$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null || true)"
    log_line "git status --porcelain"
    if [[ -z "$porcelain" ]]; then
      log_line "(clean)"
    else
      while IFS= read -r line; do
        [[ -n "$line" ]] && log_line "$line"
      done <<< "$porcelain"
    fi
    return
  fi

  head="${CLAIMS_COMMIT:-}"
  tree="${CLAIMS_TREE:-}"
  tarball_sha256="${CLAIMS_TARBALL_SHA256:-}"
  stamp_commit="$(phase0_stamp_field commit)"
  stamp_tree="$(phase0_stamp_field tree)"
  stamp_tarball="$(phase0_stamp_field tarball_sha256)"
  if [[ -z "$head" && -n "$stamp_commit" ]]; then
    head="$stamp_commit"
  fi
  if [[ -z "$tree" && -n "$stamp_tree" ]]; then
    tree="$stamp_tree"
  fi
  if [[ -z "$tarball_sha256" && -n "$stamp_tarball" ]]; then
    tarball_sha256="$stamp_tarball"
  fi

  if [[ -n "$head" ]]; then
    log_line "claims commit: ${head}"
  else
    log_line "claims commit: UNKNOWN (no .git, CLAIMS_COMMIT, or STAMP.txt commit)"
  fi
  if [[ -n "$tree" ]]; then
    log_line "claims tree: ${tree}"
  else
    log_line "claims tree: UNKNOWN (no .git, CLAIMS_TREE, or STAMP.txt tree)"
  fi
  if [[ -n "$tarball_sha256" ]]; then
    log_line "claims tarball sha256: ${tarball_sha256}"
  else
    log_line "claims tarball sha256: UNKNOWN (no CLAIMS_TARBALL_SHA256 or STAMP.txt tarball_sha256)"
  fi
}

redis_scan_claims_keys() {
  compose exec -T -e REDISCLI_AUTH="${REDIS_PASSWORD}" redis \
    redis-cli --scan --pattern 'claims*' 2>/dev/null || true
}

redis_claim_cache_key_for_id() {
  printf 'claims::%s' "$1"
}

redis_scan_contains_claim_id() {
  local claim_id="$1" expected_key scan_out
  expected_key="$(redis_claim_cache_key_for_id "$claim_id")"
  scan_out="$(redis_scan_claims_keys)"
  grep -qxF "$expected_key" <<< "$scan_out" 2>/dev/null
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

# Services with restart: "no" in docker-compose.yml (e.g. kafka-init) may exit 0 once and stay exited.
phase0_compose_oneshot_service_names() {
  awk '
    /^  [a-z0-9][a-z0-9_-]*:$/ {
      if (svc != "" && restart_no) {
        print svc
      }
      svc = $0
      sub(/^  /, "", svc)
      sub(/:$/, "", svc)
      restart_no = 0
      next
    }
    svc != "" && /^    restart: "no"/ {
      restart_no = 1
      next
    }
    /^  [a-z0-9]/ && !/^    / {
      if (svc != "" && restart_no) {
        print svc
      }
      svc = ""
      restart_no = 0
    }
    END {
      if (svc != "" && restart_no) {
        print svc
      }
    }
  ' "${REPO_ROOT}/docker-compose.yml"
}

phase0_compose_service_exit_code() {
  local svc="$1"
  compose ps -a "$svc" --format '{{.ExitCode}}' 2>/dev/null | head -n1 || true
}

phase0_is_oneshot_service() {
  local svc="$1" oneshot
  while IFS= read -r oneshot; do
    [[ -z "$oneshot" ]] && continue
    if [[ "$svc" == "$oneshot" ]]; then
      return 0
    fi
  done <<< "$(phase0_compose_oneshot_service_names)"
  return 1
}

phase0_kafka_init_container_id() {
  local svc cid
  while IFS=$'\t' read -r svc cid; do
    [[ -z "$svc" ]] && continue
    if [[ "$svc" == "kafka-init" ]]; then
      printf '%s' "$cid"
      return 0
    fi
  done <<< "$(compose ps -a --format '{{.Service}}\t{{.ID}}' 2>/dev/null || true)"
  return 1
}

phase0_docker_inspect_template() {
  local container_id="$1" template="$2"
  docker inspect -f "$template" "$container_id" 2>/dev/null || true
}

phase0_kafka_init_graded_ok() {
  local container_id status exit_code
  PHASE0_KAFKA_INIT_FAIL_REASON=""
  container_id="$(phase0_kafka_init_container_id)" || container_id=""
  if [[ -z "$container_id" ]]; then
    PHASE0_KAFKA_INIT_FAIL_REASON="kafka-init container missing in compose project ${COMPOSE_PROJECT:-<unset>}"
    return 1
  fi
  status="$(phase0_docker_inspect_template "$container_id" '{{.State.Status}}')"
  exit_code="$(phase0_docker_inspect_template "$container_id" '{{.State.ExitCode}}')"
  if [[ "$status" != "exited" ]]; then
    PHASE0_KAFKA_INIT_FAIL_REASON="kafka-init docker inspect State.Status=${status:-<empty>}, expected exited"
    return 1
  fi
  if [[ "$exit_code" != "0" ]]; then
    PHASE0_KAFKA_INIT_FAIL_REASON="kafka-init docker inspect State.ExitCode=${exit_code:-<empty>}, expected 0"
    return 1
  fi
  return 0
}

phase0_service_counts_as_healthy() {
  local svc="$1" state="$2" health="$3"
  [[ "$state" == "running" && "$health" == "healthy" ]]
}

compose_ps_status_report() {
  local svc state health
  local -a parts=()
  while IFS=$'\t' read -r svc state health; do
    [[ -z "$svc" ]] && continue
    parts+=("${svc}: state=${state} health=${health:-<empty>}")
  done <<< "$(compose_ps_lines)"
  local IFS='; '
  printf '%s' "${parts[*]+"${parts[*]}"}"
}

compose_all_services_running_healthy() {
  local svc state health saw_service=0
  while IFS=$'\t' read -r svc state health; do
    [[ -z "$svc" ]] && continue
    if phase0_is_oneshot_service "$svc"; then
      continue
    fi
    saw_service=1
    if ! phase0_service_counts_as_healthy "$svc" "$state" "$health"; then
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
    if phase0_is_oneshot_service "$svc"; then
      continue
    fi
    if phase0_service_counts_as_healthy "$svc" "$state" "$health"; then
      continue
    fi
    parts+=("${svc}(state=${state},health=${health:-empty})")
  done <<< "$(compose_ps_lines)"
  local IFS=','
  printf '%s' "${parts[*]+"${parts[*]}"}"
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
  printf '%s' "${parts[*]+"${parts[*]}"}"
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

claim_service_compose_logs() {
  compose logs claim-service 2>/dev/null || true
}

claim_service_demo_seeder_started() {
  grep -qF "${DEMO_SEEDER_LOG_MARKER}" <<< "$(claim_service_compose_logs)" 2>/dev/null
}

claim_service_demo_seeder_log_count() {
  local logs count
  logs="$(claim_service_compose_logs)"
  count="$(grep -cF "${DEMO_SEEDER_LOG_MARKER}" <<< "$logs" 2>/dev/null || true)"
  if [[ "$count" =~ ^[0-9]+$ ]]; then
    printf '%s' "$count"
  else
    printf '%s' "0"
  fi
}

actuator_body_is_up() {
  local body="$1"
  [[ "$body" == *'"status":"UP"'* ]] || [[ "$body" == *'"status": "UP"'* ]]
}

wait_for_check05_claim_service_readiness() {
  local deadline=$((SECONDS + CHECK_05_READINESS_TIMEOUT_SEC))
  local body code chunk tmp_body
  local use_readiness=1 health_path endpoint_label=""
  HTTP_WAIT_STATUS_REPORT=""
  tmp_body="$(phase0_mktemp)"
  while (( SECONDS < deadline )); do
    if [[ "$use_readiness" -eq 1 ]]; then
      health_path="${BASE_URL}/actuator/health/readiness"
      endpoint_label="/actuator/health/readiness"
    else
      health_path="${BASE_URL}/actuator/health"
      endpoint_label="/actuator/health"
    fi
    code="$(curl -sS -m 5 -o "$tmp_body" -w '%{http_code}' "${health_path}" 2>/dev/null)" || code=""
    code="$(normalize_http_code "$code")"
    PHASE0_LAST_HTTP_CODE="$code"
    body="$(cat "$tmp_body" 2>/dev/null || true)"
    if [[ "$use_readiness" -eq 1 && "$code" == "404" ]]; then
      log_line "[05] /actuator/health/readiness returned HTTP 404; falling back to /actuator/health"
      use_readiness=0
      continue
    fi
    if actuator_body_is_up "$body"; then
      log_line "[05] claim-service health poll succeeded via ${endpoint_label} (HTTP ${code})"
      return 0
    fi
    sleep 3
  done
  body="$(cat "$tmp_body" 2>/dev/null || true)"
  chunk="$(printf '%s' "$body" | head -c 200 | tr '\n' ' ')"
  HTTP_WAIT_STATUS_REPORT="check 05 readiness not UP within ${CHECK_05_READINESS_TIMEOUT_SEC}s; last HTTP ${PHASE0_LAST_HTTP_CODE}; body: $(mask_secrets "$chunk")"
  log_line "ERROR: ${HTTP_WAIT_STATUS_REPORT}"
  return 1
}

wait_for_demo_seeder_all_users() {
  local deadline=$((SECONDS + CHECK_05_DEMO_SEEDER_TIMEOUT_SEC)) count
  while (( SECONDS < deadline )); do
    count="$(claim_service_demo_seeder_log_count)"
    if [[ "$count" -ge "${DEMO_SEEDER_EXPECTED_COUNT}" ]]; then
      log_line "[05] demo seeder log lines=${count} (expected ${DEMO_SEEDER_EXPECTED_COUNT})"
      return 0
    fi
    sleep 3
  done
  count="$(claim_service_demo_seeder_log_count)"
  HTTP_WAIT_STATUS_REPORT="demo seeder: expected ${DEMO_SEEDER_EXPECTED_COUNT} log lines with ${DEMO_SEEDER_LOG_MARKER}, got ${count} within ${CHECK_05_DEMO_SEEDER_TIMEOUT_SEC}s"
  log_line "ERROR: ${HTTP_WAIT_STATUS_REPORT}"
  return 1
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
  PHASE0_REGISTER_HTTP_CODE="$code"
  [[ "$code" == "201" || "$code" == "200" ]]
}

readonly CHECK_06_CLAIM_AMOUNT="15000.00"
readonly PHASE0_BURST_CLAIM_COUNT=12
readonly PHASE0_BURST_CLAIM_AMOUNT="1000.00"
readonly PHASE0_BURST_DEADLINE_SEC=60
readonly PHASE0_KAFKA_TOPICS_FILE="${REPO_ROOT}/kafka/topics.txt"
readonly PHASE0_KAFKA_BROKER_ID="${PHASE0_KAFKA_BROKER_ID:-1}"

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

phase0_kafka_topics_sh() {
  compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "$@"
}

phase0_kafka_configs_sh() {
  compose exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 "$@"
}

phase0_expected_kafka_topics_sorted() {
  local topic
  while IFS= read -r topic || [[ -n "$topic" ]]; do
    [[ -z "$topic" ]] && continue
    printf '%s\n' "$topic"
  done < "${PHASE0_KAFKA_TOPICS_FILE}" | LC_ALL=C sort
}

phase0_kafka_topics_list_sorted() {
  phase0_kafka_topics_sh --list 2>/dev/null | grep -vE '^__' | LC_ALL=C sort
}

phase0_burst_submit_claim() {
  local jar="$1" index="$2"
  local body code
  body="$(printf '{"policyNumber":"POL-PHASE0-BURST-%02d","claimType":"AUTO","reason":"Phase0 startup burst","description":"Burst claim %02d","estimatedAmount":%s}' \
    "$index" "$index" "${PHASE0_BURST_CLAIM_AMOUNT}")"
  code="$(curl_json POST "${BASE_URL}/claims" "$jar" "$body")"
  [[ "$code" == "201" ]]
}

phase0_burst_claim_reached_next_state() {
  local status="$1"
  [[ "$status" == "APPROVED" || "$status" == "SETTLED" ]]
}

phase0_now_epoch() {
  date +%s
}

phase0_burst_deadline_elapsed() {
  local start_epoch="$1"
  local now
  now="$(phase0_now_epoch)"
  (( now - start_epoch >= PHASE0_BURST_DEADLINE_SEC ))
}

check_P1_startup_burst() {
  local nn="$1" name="$2"
  local jar_insured claim_id i status pending=1
  local -a claim_ids=() submit_epochs=() settled=() late_details=()

  jar_insured="$(phase0_mktemp)"
  if ! register_insured "phase0-burst-insured@example.com" "Phase0BurstGateOnly!" \
    "Phase0 Burst Insured" "$jar_insured"; then
    emit_result "$nn" FAIL "$name" "burst insured registration failed (HTTP ${PHASE0_REGISTER_HTTP_CODE})"
    return
  fi

  for i in $(seq 1 "${PHASE0_BURST_CLAIM_COUNT}"); do
    if ! phase0_burst_submit_claim "$jar_insured" "$i"; then
      emit_result "$nn" FAIL "$name" "burst submit claim ${i}/${PHASE0_BURST_CLAIM_COUNT} failed"
      return
    fi
    claim_id="$(claim_id_from_submit)"
    if [[ -z "$claim_id" ]]; then
      emit_result "$nn" FAIL "$name" "burst claim ${i}: could not parse claim id"
      return
    fi
    claim_ids+=("$claim_id")
    submit_epochs+=("$(phase0_now_epoch)")
    settled+=(0)
  done

  while (( pending > 0 )); do
    pending=0
    for i in $(seq 0 $((PHASE0_BURST_CLAIM_COUNT - 1))); do
      if [[ "${settled[i]}" -ne 0 ]]; then
        continue
      fi
      pending=1
      status="$(claim_status "$jar_insured" "${claim_ids[i]}" 2>/dev/null || true)"
      if phase0_burst_claim_reached_next_state "$status"; then
        settled[i]=1
        continue
      fi
      if phase0_burst_deadline_elapsed "${submit_epochs[i]}"; then
        settled[i]=2
        late_details+=("claim $((i + 1)) (${claim_ids[i]}) status=${status:-unknown}")
      fi
    done
    if (( pending > 0 )); then
      sleep 1
    fi
  done

  if ((${#late_details[@]} > 0)); then
    emit_result "$nn" FAIL "$name" "late after ${PHASE0_BURST_DEADLINE_SEC}s per submit: $(IFS='; '; echo "${late_details[*]}")"
    return
  fi
  emit_result "$nn" PASS "$name" "${PHASE0_BURST_CLAIM_COUNT} claims at ${PHASE0_BURST_CLAIM_AMOUNT} reached APPROVED or SETTLED within ${PHASE0_BURST_DEADLINE_SEC}s per claim"
}

readme_dashboard_url() {
  awk '
    /Open the secure insurance portal/ { found=1; next }
    found && /^http:\/\// { print; exit }
    found && match($0, /http:\/\/[^ ]+/) { print substr($0, RSTART, RLENGTH); exit }
  ' README.md 2>/dev/null | tr -d '`' | head -n1
}

check_P2_stack_healthy_locked_topics() {
  local nn="$1" name="$2"
  local not_healthy published host_ports fail_reasons=()
  local entry svc port mapping host_port
  local expected actual flyway_count url readme_code redis_out
  local claim_logs topics_ok=1 broker_ok=1 logs_ok=1
  local kafka_config_output

  if ! phase0_kafka_init_graded_ok; then
    emit_result "$nn" FAIL "$name" "${PHASE0_KAFKA_INIT_FAIL_REASON}"
    return
  fi

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

  local -a services_ports=(
    'postgres:5432'
    'redis:6379'
    'kafka:9092'
    'claim-service:8081'
    'approval-service:8082'
    'settlement-service:8083'
    'notification-service:8084'
  )
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
    fi
  done
  if ((${#fail_reasons[@]} > 0)); then
    emit_result "$nn" FAIL "$name" "${fail_reasons[0]}"
    return
  fi

  url="$(readme_dashboard_url)"
  if [[ -z "$url" ]]; then
    emit_result "$nn" FAIL "$name" "could not parse dashboard URL from README.md"
    return
  fi
  readme_code="$(curl_http_code "$url")"
  if [[ "$readme_code" != "200" ]]; then
    emit_result "$nn" FAIL "$name" "README dashboard GET ${url} returned HTTP ${readme_code}"
    return
  fi

  redis_out="$(compose exec -T redis redis-cli ping 2>&1 || true)"
  if [[ "$redis_out" != *NOAUTH* ]]; then
    emit_result "$nn" FAIL "$name" "redis-cli ping expected NOAUTH, got: ${redis_out//$'\n'/ }"
    return
  fi

  flyway_count="$(compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-insurance}" -tAc \
    "SELECT COUNT(*) FROM claims.flyway_schema_history_claim_service WHERE success = true;" 2>/dev/null | tr -d '[:space:]' || echo 0)"
  if ! [[ "$flyway_count" =~ ^[0-9]+$ ]] || [[ "$flyway_count" -lt 1 ]]; then
    emit_result "$nn" FAIL "$name" "flyway_schema_history_claim_service successful rows=${flyway_count}, expected at least 1"
    return
  fi

  expected="$(phase0_expected_kafka_topics_sorted)"
  actual="$(phase0_kafka_topics_list_sorted)"
  if [[ "$expected" != "$actual" ]]; then
    topics_ok=0
    log_line "kafka topic list diff (expected vs actual):"
    while IFS= read -r line; do
      [[ -n "$line" ]] && log_line "$line"
    done < <(diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true)
  fi

  kafka_config_output="$(phase0_kafka_configs_sh --describe --entity-type brokers --entity-name "${PHASE0_KAFKA_BROKER_ID}" --all 2>/dev/null || true)"
  if ! grep -qE 'auto\.create\.topics\.enable=false' <<<"$kafka_config_output"; then
    broker_ok=0
  fi

  claim_logs="$(claim_service_compose_logs)"
  if grep -q 'different partition count' <<<"$claim_logs" 2>/dev/null; then
    logs_ok=0
  fi

  if [[ "$topics_ok" -eq 1 && "$broker_ok" -eq 1 && "$logs_ok" -eq 1 ]]; then
    emit_result "$nn" PASS "$name" "healthy stack; ports locked to 8081; README 200; redis NOAUTH; flyway rows=${flyway_count}; topics match kafka/topics.txt; auto.create.topics.enable=false; kafka-init docker inspect exited(0); no partition-count warnings"
  else
    emit_result "$nn" FAIL "$name" "topics_ok=${topics_ok} broker_ok=${broker_ok} claim_logs_ok=${logs_ok}"
  fi
}

check_P3_login_cache_high_value() {
  local nn="$1" name="$2"
  local jar_a jar_b jar_broker jar_agent claim_id code status
  local active_profiles seeder_found=false seeder_count

  if ! demo_profile_enabled; then
    emit_result "$nn" FAIL "$name" "SPRING_PROFILES_ACTIVE in .env must include demo"
    return
  fi

  active_profiles="$(claim_service_spring_profiles)"
  seeder_count="$(claim_service_demo_seeder_log_count)"
  if claim_service_demo_seeder_started; then
    seeder_found=true
  fi
  log_line "[P3] claim-service SPRING_PROFILES_ACTIVE=${active_profiles:-<empty>}"
  log_line "[P3] DemoProfileSeeder log line count=${seeder_count}; grep marker found=${seeder_found}"
  if [[ "$active_profiles" != *demo* ]]; then
    emit_result "$nn" FAIL "$name" "claim-service SPRING_PROFILES_ACTIVE missing demo (got: ${active_profiles:-<empty>})"
    return
  fi
  if [[ "$seeder_found" != true ]]; then
    emit_result "$nn" FAIL "$name" "claim-service logs missing DemoProfileSeeder line (${DEMO_SEEDER_LOG_MARKER}…)"
    return
  fi

  jar_agent="$(phase0_mktemp)"
  if ! login_user "agent@insureflow.local" "${DEMO_AGENT_PASSWORD}" "$jar_agent"; then
    log_http_failure "[P3] AGENT login" "${PHASE0_LAST_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "AGENT login failed ($(http_failure_appendix "${PHASE0_LAST_HTTP_CODE}"))"
    return
  fi

  jar_a="$(phase0_mktemp)"
  jar_b="$(phase0_mktemp)"
  jar_broker="$(phase0_mktemp)"

  if ! register_insured "p3-insured-a-phase0-$(date +%s)@example.com" "${DEMO_INSURED_PASSWORD}" "P3 Insured A" "$jar_a"; then
    log_http_failure "[P3] INSURED A registration" "${PHASE0_REGISTER_HTTP_CODE}"
    emit_result "$nn" FAIL "$name" "INSURED A registration failed (HTTP ${PHASE0_REGISTER_HTTP_CODE})"
    return
  fi
  if ! submit_claim_check_06 "$jar_a"; then
    emit_result "$nn" FAIL "$name" "INSURED A claim submit failed (${CHECK_06_CLAIM_AMOUNT} above 10k auto-approve limit)"
    return
  fi
  claim_id="$(claim_id_from_submit)"
  if [[ -z "$claim_id" ]]; then
    emit_result "$nn" FAIL "$name" "could not parse claim id from submit response"
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
    emit_result "$nn" FAIL "$name" "after-read: claims* key for claim missing after AGENT read"
    return
  fi
  status="$(claim_status "$jar_a" "$claim_id" || true)"
  if [[ "$status" != "SUBMITTED" ]]; then
    emit_result "$nn" FAIL "$name" "claim ${CHECK_06_CLAIM_AMOUNT} above 10k limit status=${status:-unknown}, expected SUBMITTED before AGENT decide"
    return
  fi

  if ! register_insured "p3-insured-b-phase0-$(date +%s)@example.com" "${DEMO_INSURED_PASSWORD}" "P3 Insured B" "$jar_b"; then
    emit_result "$nn" FAIL "$name" "INSURED B registration failed"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_b")"
  if [[ "$code" != "404" ]]; then
    emit_result "$nn" FAIL "$name" "INSURED B GET returned HTTP ${code}, expected 404"
    return
  fi

  if ! login_user "broker@insureflow.local" "${DEMO_BROKER_PASSWORD}" "$jar_broker"; then
    emit_result "$nn" FAIL "$name" "BROKER login failed"
    return
  fi
  code="$(curl_json GET "${BASE_URL}/claims/${claim_id}" "$jar_broker")"
  if [[ "$code" != "404" ]]; then
    emit_result "$nn" FAIL "$name" "BROKER GET returned HTTP ${code}, expected 404"
    return
  fi

  code="$(curl_json POST "${BASE_URL}/claims/${claim_id}/decision" "$jar_agent" \
    '{"approved":true,"reason":"Phase0 P3 cache eviction over 10k"}')"
  if [[ "$code" != "200" ]]; then
    emit_result "$nn" FAIL "$name" "AGENT decide returned HTTP ${code}"
    return
  fi
  log_check_06_redis_scan "after-decide"
  if redis_scan_contains_claim_id "$claim_id"; then
    emit_result "$nn" FAIL "$name" "after-decide: claims* key still present (scan before AGENT re-read)"
    return
  fi
  status="$(claim_status "$jar_agent" "$claim_id" || true)"
  if [[ "$status" != "APPROVED" ]]; then
    emit_result "$nn" FAIL "$name" "after re-read status=${status:-unknown}, expected APPROVED"
    return
  fi
  log_check_06_redis_scan "after-reread"
  if ! redis_scan_contains_claim_id "$claim_id"; then
    emit_result "$nn" FAIL "$name" "after-reread: claims* key missing after AGENT GET"
    return
  fi
  emit_result "$nn" PASS "$name" "demo seeder + AGENT login; ${CHECK_06_CLAIM_AMOUNT} stayed SUBMITTED; cache isolation; decide evicted; APPROVED with cache repopulated"
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
  local spec nn display fn
  for spec in "${CHECKS[@]}"; do
    IFS='|' read -r nn display fn <<< "$spec"
    "$fn" "$nn" "$display" || :
  done
}

print_summary() {
  log_line "SUMMARY pass=${PASS_COUNT} fail=${FAIL_COUNT} skipped=${SKIP_COUNT} total=${#CHECKS[@]}"
}

list_checks() {
  local spec nn display _fn
  for spec in "${CHECKS[@]}"; do
    IFS='|' read -r nn display _fn <<< "$spec"
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

  if ! demo_profile_enabled; then
    printf 'Phase 0 requires SPRING_PROFILES_ACTIVE to include demo in .env\n' >&2
    exit 1
  fi

  if phase0_no_teardown_enabled; then
    PHASE0_ACTIVE_COMPOSE_PROJECT=""
    log_line "PHASE0_NO_TEARDOWN=1: per-phase compose projects; skipping down/rm teardown"
    phase0_init_and_validate_no_teardown_projects
    phase0_compose_stop_project "${REQUIRED_COMPOSE_PROJECT}"
    phase0_switch_compose_project "${PHASE0_PROJECT_STARTUP}"
    phase0_log_phase_compose_header "startup"
    log_line "compose up -d --build demo profile (project=${PHASE0_ACTIVE_COMPOSE_PROJECT})"
    compose_with_profile demo up -d --build
  else
    log_line "compose down -v (throwaway demo stack)"
    compose down -v
    log_line "compose up -d --build demo profile"
    compose_with_profile demo up -d --build
  fi

  if ! wait_for_stack_ready; then
    log_line "WARN: initial stack readiness timed out (health and/or claim-service HTTP); running checks anyway"
  fi
  if ! wait_for_check05_claim_service_readiness; then
    log_line "WARN: demo claim-service readiness timed out: ${HTTP_WAIT_STATUS_REPORT}"
  fi
  if ! wait_for_demo_seeder_all_users; then
    log_line "WARN: demo seeder wait timed out: ${HTTP_WAIT_STATUS_REPORT}"
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
