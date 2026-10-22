#!/usr/bin/env bash
# Stub tests for phase0-runtime-check (no real Docker). Run from repo root.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export COMPOSE_PROJECT=phase0check
PHASE0_STUB_ORIGINAL_PATH="${PATH}"
export PHASE0_ORIGINAL_PATH="${PHASE0_STUB_ORIGINAL_PATH}"

STUB_BIN="$(mktemp -d)"
STUB_DOCKER_LOG=""
STUB_DEMO_ENV_ERR=""

phase0_stub_cleanup_on_exit() {
  if phase0_no_teardown_enabled; then
    printf 'PHASE0_NO_TEARDOWN stub: leaving STUB_BIN=%s on disk\n' "$STUB_BIN" >&2
    if [[ -n "$STUB_DOCKER_LOG" && -f "$STUB_DOCKER_LOG" ]]; then
      printf 'PHASE0_NO_TEARDOWN stub: leaving DOCKER_STUB_LOG=%s on disk\n' "$STUB_DOCKER_LOG" >&2
    fi
    if [[ -n "$STUB_DEMO_ENV_ERR" && -f "$STUB_DEMO_ENV_ERR" ]]; then
      printf 'PHASE0_NO_TEARDOWN stub: leaving STUB_DEMO_ENV_ERR=%s on disk\n' "$STUB_DEMO_ENV_ERR" >&2
    fi
    return 0
  fi
  rm -rf "$STUB_BIN"
  if [[ -n "$STUB_DOCKER_LOG" && -f "$STUB_DOCKER_LOG" ]]; then
    rm -f "$STUB_DOCKER_LOG"
  fi
  if [[ -n "$STUB_DEMO_ENV_ERR" && -f "$STUB_DEMO_ENV_ERR" ]]; then
    rm -f "$STUB_DEMO_ENV_ERR"
  fi
}

trap phase0_stub_cleanup_on_exit EXIT

# True when a logged docker compose line uses down/rm as whole-word subcommands (not inside paths).
phase0_stub_compose_log_has_forbidden_teardown() {
  local log_file="$1"
  grep -qE '(^|[[:space:]])down([[:space:]]|$)' "$log_file" && return 0
  grep -qE '(^|[[:space:]])rm([[:space:]]|$)' "$log_file" && return 0
  return 1
}

# Fake docker CLI: compose version OK; config -q fails when -p project name contains uppercase.
phase0_stub_install_fake_compose_cli() {
  cat > "${STUB_BIN}/docker-stub-compose" <<'STUBCOMPOSE'
#!/usr/bin/env bash
if [[ "${1:-}" != "compose" ]]; then
  exit 1
fi
shift
if [[ "${1:-}" == "version" ]]; then
  echo "Docker Compose version v2.30.0-stub"
  exit 0
fi
project=""
args=("$@")
i=0
while (( i < ${#args[@]} )); do
  if [[ "${args[i]}" == "-p" ]]; then
    project="${args[i + 1]}"
    break
  fi
  i=$((i + 1))
done
if [[ -n "$project" && "$project" == *[[:upper:]]* ]]; then
  printf 'stub compose: project name %q contains uppercase\n' "$project" >&2
  exit 1
fi
exit 0
STUBCOMPOSE
  chmod +x "${STUB_BIN}/docker-stub-compose"
}

# Emits many log lines then nine demo seeder markers (exercises pipefail-safe log grep).
phase0_stub_install_high_volume_compose_logs_cli() {
  cat > "${STUB_BIN}/docker" <<'STUBLOGS'
#!/usr/bin/env bash
if [[ "${1:-}" != "compose" ]]; then
  exit 1
fi
shift
if [[ " $* " == *" logs "* ]]; then
  i=0
  while (( i < 2500 )); do
    printf 'filler claim-service log line %s\n' "$i"
    i=$((i + 1))
  done
  n=1
  while (( n <= 9 )); do
    printf 'Demo portal user ready: email=stub-user-%s@insureflow.local, role=STUB\n' "$n"
    n=$((n + 1))
  done
  exit 0
fi
exit 0
STUBLOGS
  chmod +x "${STUB_BIN}/docker"
}

cat > "${STUB_BIN}/curl" <<'EOF'
#!/usr/bin/env bash
# Fake curl: actuator UP; other URLs simulate empty reply (exit 52) with http_code 000.
args=("$@")
url=""
for a in "${args[@]}"; do
  case "$a" in
    http://*|https://*) url="$a" ;;
  esac
done
if [[ "$url" == *'/actuator/health' ]]; then
  out_file=""
  local_i=0
  while (( local_i < ${#args[@]} )); do
    if [[ "${args[local_i]}" == "-o" ]]; then
      out_file="${args[local_i + 1]}"
      break
    fi
    local_i=$((local_i + 1))
  done
  if [[ -n "$out_file" ]]; then
    printf '%s' '{"status":"UP"}' > "$out_file"
  fi
  if [[ " ${args[*]} " == *" -w "* ]]; then
    printf '%s' '200'
    exit 0
  fi
  printf '%s\n' '{"status":"UP"}'
  exit 0
fi
if [[ " ${args[*]} " == *" -w "* ]]; then
  printf '%s' '000'
  exit 52
fi
exit 52
EOF
chmod +x "${STUB_BIN}/curl"

# shellcheck source=scripts/phase0-runtime-check.sh
source "${REPO_ROOT}/scripts/phase0-runtime-check.sh"

if [[ "${PHASE0_NO_TEARDOWN:-}" == "1" ]]; then
  echo "stub: PHASE0_NO_TEARDOWN skips stubtest EXIT rm cleanup"
fi

echo "stub: empty PHASE0_TMP_FILES cleanup under set -u"
PHASE0_TMP_FILES=()
phase0_cleanup_tmps

echo "stub: normalize_http_code collapses bad codes to 000"
[[ "$(normalize_http_code '200')" == "200" ]]
[[ "$(normalize_http_code '000000')" == "000" ]]
[[ "$(normalize_http_code '')" == "000" ]]

echo "stub: curl_http_code returns single 000 on failure"
export PATH="${STUB_BIN}:${PATH}"
PHASE0_CURL_BODY="$(mktemp)"
phase0_register_tmp "$PHASE0_CURL_BODY"
code="$(curl_http_code "http://localhost:8081/dashboard")"
[[ "$code" == "000" ]]

echo "stub: wait_for_claim_service_http succeeds when actuator returns UP"
if ! wait_for_claim_service_http; then
  echo "FAIL: wait_for_claim_service_http" >&2
  exit 1
fi

echo "stub: demo seeder log grep survives pipefail with high-volume compose logs"
phase0_stub_install_high_volume_compose_logs_cli
export PATH="${STUB_BIN}:${PHASE0_STUB_ORIGINAL_PATH}"
export COMPOSE_PROJECT=phase0check
unset PHASE0_NO_TEARDOWN
seeder_log_count="$(claim_service_demo_seeder_log_count)"
if [[ "$seeder_log_count" -lt 9 ]]; then
  echo "FAIL: expected at least 9 demo seeder log lines under pipefail, got ${seeder_log_count}" >&2
  exit 1
fi
if ! claim_service_demo_seeder_started; then
  echo "FAIL: claim_service_demo_seeder_started should pass with high-volume logs under pipefail" >&2
  exit 1
fi
echo "stub: demo seeder log grep OK under pipefail (count=${seeder_log_count})"

echo "stub: collect_demo_password_env_names_from_fixtures finds 9 persona passwords"
collect_demo_password_env_names_from_fixtures "$(persona_fixtures_dir)"
if ((${#DEMO_PASSWORD_ENV_NAMES[@]} != 9)); then
  echo "FAIL: expected 9 demo password env names, got ${#DEMO_PASSWORD_ENV_NAMES[@]}" >&2
  exit 1
fi

echo "stub: empty fixtures dir exits 2 with zero password env names"
empty_fixtures="$(mktemp -d)"
zero_rc=0
collect_demo_password_env_names_from_fixtures "$empty_fixtures" >/dev/null 2>&1 || zero_rc=$?
if [[ "$zero_rc" -ne 2 ]]; then
  echo "FAIL: empty fixtures dir should exit 2, got ${zero_rc}" >&2
  exit 1
fi

demo_env_err="$(mktemp)"
if [[ "${PHASE0_NO_TEARDOWN:-}" != "1" ]]; then
  STUB_DEMO_ENV_ERR="$demo_env_err"
fi

echo "stub: require_demo_env_vars_when_enabled fails when a demo password is unset"
export SPRING_PROFILES_ACTIVE=demo
collect_demo_password_env_names_from_fixtures "$(persona_fixtures_dir)"
for v in "${DEMO_PASSWORD_ENV_NAMES[@]}"; do
  export "${v}=stub-value-not-in-output"
done
unset DEMO_SIU_PASSWORD
unset_rc=0
( require_demo_env_vars_when_enabled ) 2>"$demo_env_err" || unset_rc=$?
if [[ "$unset_rc" -ne 1 ]]; then
  echo "FAIL: unset DEMO_SIU_PASSWORD should exit 1, got ${unset_rc}" >&2
  exit 1
fi
if ! grep -q 'DEMO_SIU_PASSWORD' "$demo_env_err"; then
  echo "FAIL: error should name DEMO_SIU_PASSWORD" >&2
  exit 1
fi
if grep -q 'stub-value-not-in-output' "$demo_env_err"; then
  echo "FAIL: error must not print password values" >&2
  exit 1
fi

echo "stub: require_demo_env_vars_when_enabled fails when a demo password is set but empty"
for v in "${DEMO_PASSWORD_ENV_NAMES[@]}"; do
  export "${v}=stub-value-not-in-output"
done
export DEMO_SIU_PASSWORD=
empty_rc=0
( require_demo_env_vars_when_enabled ) 2>"$demo_env_err" || empty_rc=$?
if [[ "$empty_rc" -ne 1 ]]; then
  echo "FAIL: empty DEMO_SIU_PASSWORD= should exit 1, got ${empty_rc}" >&2
  exit 1
fi
if ! grep -q 'DEMO_SIU_PASSWORD' "$demo_env_err"; then
  echo "FAIL: empty-password error should name DEMO_SIU_PASSWORD" >&2
  exit 1
fi

echo "stub: require_demo_env_vars_when_enabled passes when all 9 persona passwords are set"
for v in "${DEMO_PASSWORD_ENV_NAMES[@]}"; do
  export "${v}=stub-value-not-in-output"
done
all_ok_rc=0
( require_demo_env_vars_when_enabled ) || all_ok_rc=$?
if [[ "$all_ok_rc" -ne 0 ]]; then
  echo "FAIL: all nine demo passwords set should succeed" >&2
  exit 1
fi

echo "stub: collect_demo_password_env_names uses portable sed -En extraction"
collect_demo_password_env_names_from_fixtures "$(persona_fixtures_dir)"
if ((${#DEMO_PASSWORD_ENV_NAMES[@]} != 9)); then
  echo "FAIL: sed guard extraction expected 9 names, got ${#DEMO_PASSWORD_ENV_NAMES[@]}" >&2
  exit 1
fi

echo "stub: PHASE0_NO_TEARDOWN leaves temp files and logs paths"
export PHASE0_NO_TEARDOWN=1
LOG_FILE="$(mktemp)"
phase0_register_tmp "$LOG_FILE"
left_tmp="$(mktemp)"
phase0_register_tmp "$left_tmp"
phase0_cleanup_tmps
if [[ ! -f "$left_tmp" ]]; then
  echo "FAIL: PHASE0_NO_TEARDOWN must not rm -f temp files" >&2
  exit 1
fi
if ! grep -q "$left_tmp" "$LOG_FILE"; then
  echo "FAIL: PHASE0_NO_TEARDOWN cleanup should log temp path" >&2
  exit 1
fi
if [[ "${PHASE0_NO_TEARDOWN:-}" != "1" ]]; then
  rm -f "$left_tmp" "$LOG_FILE"
fi
unset PHASE0_NO_TEARDOWN

echo "stub: no-teardown project names (single ts) regex and real compose config -q"
export PHASE0_NO_TEARDOWN=1
export COMPOSE_PROJECT=phase0check
PHASE0_COMPOSE_RUN_TS="20261011t120000z"
p_startup="$(phase0_compose_project_for_phase startup)"
p_c04="$(phase0_compose_project_for_phase c04)"
p_c05="$(phase0_compose_project_for_phase c05)"
for pn in "$p_startup" "$p_c04" "$p_c05"; do
  if ! phase0_runtime_compose_project_name_valid "$pn"; then
    echo "FAIL: generated name ${pn} failed ^phase0check(-[0-9a-z_-]+)+$" >&2
    exit 1
  fi
done
if [[ "$p_startup" != phase0check-20261011t120000z-startup ]] \
  || [[ "$p_c04" != phase0check-20261011t120000z-c04 ]] \
  || [[ "$p_c05" != phase0check-20261011t120000z-c05 ]]; then
  echo "FAIL: startup/c04/c05 must share one timestamp prefix" >&2
  exit 1
fi
bad_name_upper="phase0check-20261011T183536Z-startup"
if phase0_runtime_compose_project_name_valid "$bad_name_upper"; then
  echo "FAIL: uppercase project name must be rejected by runtime regex" >&2
  exit 1
fi

if ! phase0_resolve_compose_executable; then
  if [[ "${PHASE0_ALLOW_SKIP:-}" == "1" ]]; then
    echo "SKIP: real docker compose CLI not available; config -q name validation not run (this is not a pass)"
  else
    echo "FAIL: real docker compose CLI required for stub test (set PHASE0_ALLOW_SKIP=1 to skip)" >&2
    exit 1
  fi
else
  export PHASE0_DOCKER_BIN="${PHASE0_RESOLVED_COMPOSE_BIN}"
  for pn in "$p_startup" "$p_c04" "$p_c05"; do
    if ! phase0_validate_suffixed_compose_project_name "$pn" >/dev/null; then
      echo "FAIL: real docker compose config -q rejected valid name ${pn}" >&2
      exit 1
    fi
  done
  echo "real docker compose config -q validation ran for startup, c04, c05"
fi

phase0_stub_install_fake_compose_cli
echo "stub: compose-only validator rejects uppercase project names (fake compose CLI via PHASE0_DOCKER_BIN)"
export PHASE0_DOCKER_BIN="${STUB_BIN}/docker-stub-compose"
PHASE0_RESOLVED_COMPOSE_BIN=""
phase0_compose_use_standalone=0
compose_upper_rc=0
phase0_validate_compose_project_name_compose_only "$bad_name_upper" >/dev/null 2>&1 || compose_upper_rc=$?
if [[ "$compose_upper_rc" -ne 2 ]]; then
  echo "FAIL: compose-only uppercase validation should return 2, got ${compose_upper_rc}" >&2
  exit 1
fi
echo "stub: compose-only validator rejected uppercase project name (exit ${compose_upper_rc})"

echo "stub: compose-only validator under errexit (caller sees rc=2 and continues)"
under_errexit_rc=0
phase0_validate_compose_project_name_compose_only "$bad_name_upper" >/dev/null 2>&1 || under_errexit_rc=$?
if [[ "$under_errexit_rc" -ne 2 ]]; then
  echo "FAIL: compose-only under errexit should return 2, got ${under_errexit_rc}" >&2
  exit 1
fi
echo "stub: compose-only validator under errexit returned rc=2; caller continued"

echo "stub: PHASE0_NO_TEARDOWN startup project switch (no extra c04/c05 stacks)"
echo "stub: PHASE0_NO_TEARDOWN does not stop projects outside allowlist"
STUB_DOCKER_LOG="$(mktemp)"
DOCKER_STUB_LOG="$STUB_DOCKER_LOG"
export DOCKER_STUB_LOG
cat > "${STUB_BIN}/docker" <<'DOCKERSTUB2'
#!/usr/bin/env bash
printf '%s\n' "docker $*" >> "${DOCKER_STUB_LOG}"
exit 0
DOCKERSTUB2
chmod +x "${STUB_BIN}/docker"
export PATH="${STUB_BIN}:${PATH}"
: >"$DOCKER_STUB_LOG"
phase0_compose_stop_project "phase0check-not-created-this-run"
if grep -q 'phase0check-not-created-this-run' "$DOCKER_STUB_LOG"; then
  echo "FAIL: must not stop projects not created this run" >&2
  exit 1
fi

echo "stub: PHASE0_NO_TEARDOWN guard accepts phase0check- suffix"
saved_compose_project="${COMPOSE_PROJECT}"
saved_no_teardown="${PHASE0_NO_TEARDOWN:-}"
bad_rc=0
# Subshell isolates require_compose_project_guard exit 2 from killing the stub driver.
# shellcheck disable=SC2030
(
  export COMPOSE_PROJECT=phase0check-extra
  unset PHASE0_NO_TEARDOWN
  require_compose_project_guard
) 2>/dev/null || bad_rc=$?
good_rc=0
PHASE0_NO_TEARDOWN=1 COMPOSE_PROJECT=phase0check-extra require_compose_project_guard || good_rc=$?
# shellcheck disable=SC2031
export COMPOSE_PROJECT="${saved_compose_project}"
if [[ -n "$saved_no_teardown" ]]; then
  export PHASE0_NO_TEARDOWN="${saved_no_teardown}"
else
  unset PHASE0_NO_TEARDOWN
fi
if [[ "$bad_rc" -eq 0 ]]; then
  echo "FAIL: phase0check-extra should fail guard without PHASE0_NO_TEARDOWN" >&2
  exit 1
fi
if [[ "$good_rc" -ne 0 ]]; then
  echo "FAIL: phase0check-extra should pass guard with PHASE0_NO_TEARDOWN=1" >&2
  exit 1
fi

if [[ "${PHASE0_NO_TEARDOWN:-}" != "1" ]]; then
  rm -f "$DOCKER_STUB_LOG"
fi

phase0_stub_assert_empty_stamp_field_succeeds_under_errexit() {
  local work_dir="$1" stamp_body="$2"
  local probe_rc=0 probe_out=""

  rm -rf "$work_dir"
  mkdir -p "$work_dir"
  printf '%s' "$stamp_body" > "${work_dir}/STAMP.txt"
  probe_out="$(
    set -euo pipefail
    REPO_ROOT="$work_dir"
    phase0_stamp_field tarball_sha256 >/dev/null
    printf continued
  )" 2>/dev/null || probe_rc=$?
  if [[ "$probe_rc" -ne 0 ]]; then
    echo "FAIL: empty STAMP tarball_sha256 must not fail under errexit (rc=${probe_rc})" >&2
    return 1
  fi
  if [[ "$probe_out" != "continued" ]]; then
    echo "FAIL: phase0_stamp_field should allow caller to continue under errexit" >&2
    return 1
  fi
  return 0
}

phase0_stub_run_log_repo_git_state_no_git() {
  local work_dir="$1"
  local claims_commit="${2-}"
  local claims_tree="${3-}"
  local claims_tarball="${4-}"
  local stamp_body="${5-}"
  local out_file rc=0

  rm -rf "$work_dir"
  mkdir -p "$work_dir"
  if [[ -n "$stamp_body" ]]; then
    printf '%s' "$stamp_body" > "${work_dir}/STAMP.txt"
  fi
  out_file="$(mktemp)"
  phase0_register_tmp "$out_file"
  (
    set -euo pipefail
    if [[ -n "$claims_commit" ]]; then
      export CLAIMS_COMMIT="$claims_commit"
    else
      unset CLAIMS_COMMIT
    fi
    if [[ -n "$claims_tree" ]]; then
      export CLAIMS_TREE="$claims_tree"
    else
      unset CLAIMS_TREE
    fi
    if [[ -n "$claims_tarball" ]]; then
      export CLAIMS_TARBALL_SHA256="$claims_tarball"
    else
      unset CLAIMS_TARBALL_SHA256
    fi
    REPO_ROOT="$work_dir"
    log_repo_git_state
  ) >"$out_file" 2>&1 || rc=$?
  printf '%s' "$rc"
  printf '\n'
  cat "$out_file"
}

echo "stub: log_repo_git_state without .git uses CLAIMS_* when STAMP tarball_sha256 is empty"
stamp_work="$(mktemp -d)"
if ! phase0_stub_assert_empty_stamp_field_succeeds_under_errexit \
  "$stamp_work" \
  $'commit=stampcommit\ntree=stamptree\ntarball_sha256=\n'; then
  exit 1
fi
stamp_out="$(phase0_stub_run_log_repo_git_state_no_git \
  "$stamp_work" \
  "abc123commit" \
  "def456tree" \
  "sha789tarball" \
  $'commit=stampcommit\ntree=stamptree\ntarball_sha256=\n')"
stamp_rc="${stamp_out%%$'\n'*}"
stamp_log="${stamp_out#*$'\n'}"
if [[ "$stamp_rc" -ne 0 ]]; then
  echo "FAIL: log_repo_git_state should not exit early when STAMP tarball_sha256 is empty (rc=${stamp_rc})" >&2
  echo "$stamp_log" >&2
  exit 1
fi
if ! grep -q 'claims commit: abc123commit' <<<"$stamp_log"; then
  echo "FAIL: expected CLAIMS_COMMIT in log_repo_git_state output" >&2
  echo "$stamp_log" >&2
  exit 1
fi
if ! grep -q 'claims tree: def456tree' <<<"$stamp_log"; then
  echo "FAIL: expected CLAIMS_TREE in log_repo_git_state output" >&2
  echo "$stamp_log" >&2
  exit 1
fi
if ! grep -q 'claims tarball sha256: sha789tarball' <<<"$stamp_log"; then
  echo "FAIL: expected CLAIMS_TARBALL_SHA256 in log_repo_git_state output" >&2
  echo "$stamp_log" >&2
  exit 1
fi

echo "stub: log_repo_git_state without .git prints UNKNOWN when claims and STAMP are empty"
empty_work="$(mktemp -d)"
empty_out="$(phase0_stub_run_log_repo_git_state_no_git "$empty_work" "" "" "" "")"
empty_rc="${empty_out%%$'\n'*}"
empty_log="${empty_out#*$'\n'}"
if [[ "$empty_rc" -ne 0 ]]; then
  echo "FAIL: log_repo_git_state should continue with UNKNOWN claims (rc=${empty_rc})" >&2
  echo "$empty_log" >&2
  exit 1
fi
if ! grep -q 'claims commit: UNKNOWN' <<<"$empty_log"; then
  echo "FAIL: expected UNKNOWN commit line" >&2
  echo "$empty_log" >&2
  exit 1
fi
if ! grep -q 'claims tree: UNKNOWN' <<<"$empty_log"; then
  echo "FAIL: expected UNKNOWN tree line" >&2
  echo "$empty_log" >&2
  exit 1
fi
if ! grep -q 'claims tarball sha256: UNKNOWN' <<<"$empty_log"; then
  echo "FAIL: expected UNKNOWN tarball line" >&2
  echo "$empty_log" >&2
  exit 1
fi

echo "stub: CHECK_06_CLAIM_AMOUNT is 15000.00"
if [[ "${CHECK_06_CLAIM_AMOUNT}" != "15000.00" ]]; then
  echo "FAIL: CHECK_06_CLAIM_AMOUNT=${CHECK_06_CLAIM_AMOUNT}, expected 15000.00" >&2
  exit 1
fi

phase0_kafka_topics_list_sorted() {
  printf '%s\n' "${PHASE0_STUB_KAFKA_LIST[@]:-}" | LC_ALL=C sort
}

phase0_kafka_configs_sh() {
  printf '%s\n' "${PHASE0_STUB_KAFKA_CONFIGS_OUTPUT:-}"
  return 0
}

claim_service_compose_logs() {
  printf '%s\n' "${PHASE0_STUB_CLAIM_SERVICE_LOGS:-}"
}

phase0_stub_compose_ps_kafka_init() {
  printf '%s\n' "${PHASE0_STUB_KAFKA_INIT_STATE:-exited}"
  printf '%s\n' "${PHASE0_STUB_KAFKA_INIT_EXIT:-0}"
}

compose() {
  if [[ "${1:-}" == "ps" && "${2:-}" == "-a" && "${3:-}" == "--format" && "${4:-}" == '{{.Service}}\t{{.State}}\t{{.Health}}' ]]; then
    if [[ -z "${PHASE0_STUB_PS_LINES:-}" ]]; then
      printf '%s\n' $'postgres\trunning\thealthy'
    else
      printf '%s\n' "${PHASE0_STUB_PS_LINES}"
    fi
    return 0
  fi
  if [[ "${1:-}" == "ps" && "${2:-}" == "--format" && "${3:-}" == "{{.Ports}}" ]]; then
    printf '%s\n' "${PHASE0_STUB_COMPOSE_PORTS:-0.0.0.0:8081->8081/tcp}"
    return 0
  fi
  if [[ "${1:-}" == "port" ]]; then
    if [[ "${2:-}" == "claim-service" && "${3:-}" == "8081" ]]; then
      printf '%s\n' "${PHASE0_STUB_CLAIM_PORT:-0.0.0.0:8081}"
      return 0
    fi
    return 0
  fi
  if [[ "${1:-}" == "exec" && "${3:-}" == "redis" ]]; then
    printf 'NOAUTH Authentication required.\n'
    return 0
  fi
  if [[ "${1:-}" == "exec" && "${3:-}" == "postgres" ]]; then
    printf '1\n'
    return 0
  fi
  if [[ "${1:-}" == "ps" && "${2:-}" == "-a" && "${3:-}" == "--format" && "${4:-}" == '{{.Service}}\t{{.ID}}' ]]; then
    if [[ -n "${PHASE0_STUB_KAFKA_INIT_CID:-}" ]]; then
      printf 'kafka-init\t%s\n' "${PHASE0_STUB_KAFKA_INIT_CID}"
    fi
    return 0
  fi
  if [[ "${1:-}" == "ps" && "${2:-}" == "-a" && "${3:-}" == "kafka-init" ]]; then
    if [[ "${4:-}" == "--format" && "${5:-}" == "{{.State}}" ]]; then
      phase0_stub_compose_ps_kafka_init | head -n1
      return 0
    fi
    if [[ "${4:-}" == "--format" && "${5:-}" == "{{.ExitCode}}" ]]; then
      phase0_stub_compose_ps_kafka_init | tail -n1
      return 0
    fi
  fi
  command docker compose "$@"
}

phase0_stub_curl_http_code() {
  local url="$1"
  if [[ "$url" == *'/actuator/health'* ]] || [[ "$url" == *'dashboard'* ]]; then
    printf '200'
    return 0
  fi
  printf '000'
}
curl_http_code() { phase0_stub_curl_http_code "$1"; }

phase0_docker_inspect_template() {
  local container_id="$1" template="$2"
  if [[ "$template" == '{{.State.Status}}' ]]; then
    printf '%s' "${PHASE0_STUB_KAFKA_INIT_INSPECT_STATUS:-exited}"
    return 0
  fi
  if [[ "$template" == '{{.State.ExitCode}}' ]]; then
    printf '%s' "${PHASE0_STUB_KAFKA_INIT_INSPECT_EXIT:-0}"
    return 0
  fi
  printf ''
}

export POSTGRES_USER=postgres
export POSTGRES_DB=insurance

echo "stub: kafka-init docker inspect exited 0 passes P2 grading"
PHASE0_STUB_KAFKA_INIT_CID=cid-kafka-init-ok
PHASE0_STUB_KAFKA_INIT_INSPECT_STATUS=exited
PHASE0_STUB_KAFKA_INIT_INSPECT_EXIT=0
if ! phase0_kafka_init_graded_ok; then
  echo "FAIL: kafka-init exited 0 should pass inspect grading: ${PHASE0_KAFKA_INIT_FAIL_REASON}" >&2
  exit 1
fi

echo "stub: kafka-init docker inspect exited 1 fails P2 grading"
PHASE0_STUB_KAFKA_INIT_INSPECT_EXIT=1
if phase0_kafka_init_graded_ok; then
  echo "FAIL: kafka-init exit 1 should fail inspect grading" >&2
  exit 1
fi
if [[ "${PHASE0_KAFKA_INIT_FAIL_REASON}" != *"ExitCode=1"* ]]; then
  echo "FAIL: expected ExitCode=1 in reason, got: ${PHASE0_KAFKA_INIT_FAIL_REASON}" >&2
  exit 1
fi

echo "stub: kafka-init missing container fails P2 grading"
unset PHASE0_STUB_KAFKA_INIT_CID
if phase0_kafka_init_graded_ok; then
  echo "FAIL: missing kafka-init container should fail inspect grading" >&2
  exit 1
fi
if [[ "${PHASE0_KAFKA_INIT_FAIL_REASON}" != *"missing"* ]]; then
  echo "FAIL: expected missing-container reason, got: ${PHASE0_KAFKA_INIT_FAIL_REASON}" >&2
  exit 1
fi
PHASE0_STUB_KAFKA_INIT_CID=cid-kafka-init-ok
PHASE0_STUB_KAFKA_INIT_INSPECT_EXIT=0

echo "stub: phase0-runtime-check.sh has no DEL/FLUSH cache purge commands"
if rg -n 'DEL |FLUSH' "${REPO_ROOT}/scripts/phase0-runtime-check.sh" >/tmp/phase0-rg-del-flush.txt 2>/dev/null; then
  cat /tmp/phase0-rg-del-flush.txt
  echo "FAIL: DEL/FLUSH must not appear in phase0-runtime-check.sh" >&2
  exit 1
fi
echo "stub: rg -n 'DEL |FLUSH' scripts/phase0-runtime-check.sh produced no matches"

echo "stub: kafka topic pre-create check passes with matching topic list and broker config"
PHASE0_STUB_KAFKA_LIST=(
  claim.analysis.completed
  claim.analysis.requested
  claim.approved
  claim.approved.DLT
  claim.manual-decision
  claim.rejected
  claim.settled
  claim.submitted
)
PHASE0_STUB_KAFKA_CONFIGS_OUTPUT='auto.create.topics.enable=false	'
PHASE0_STUB_KAFKA_INIT_CID=cid-kafka-init-p2
PHASE0_STUB_KAFKA_INIT_INSPECT_STATUS=exited
PHASE0_STUB_KAFKA_INIT_INSPECT_EXIT=0
PHASE0_STUB_PS_LINES=$'postgres\trunning\thealthy\n'
PHASE0_STUB_CLAIM_SERVICE_LOGS='claim-service started'
stub_pass_before="$PASS_COUNT"
stub_fail_before="$FAIL_COUNT"
check_P2_stack_healthy_locked_topics "17" "stub P2 kafka topics" || true
if [[ "$PASS_COUNT" -ne $((stub_pass_before + 1)) || "$FAIL_COUNT" -ne "$stub_fail_before" ]]; then
  echo "FAIL: expected check P2 PASS in stub (pass=${PASS_COUNT} fail=${FAIL_COUNT})" >&2
  exit 1
fi
PASS_COUNT="$stub_pass_before"
FAIL_COUNT="$stub_fail_before"

echo "stub: kafka topic pre-create check fails on topic mismatch"
PHASE0_STUB_KAFKA_LIST=(claim.submitted)
stub_pass_before="$PASS_COUNT"
stub_fail_before="$FAIL_COUNT"
check_P2_stack_healthy_locked_topics "17" "stub P2 kafka topics mismatch" || true
if [[ "$FAIL_COUNT" -ne $((stub_fail_before + 1)) || "$PASS_COUNT" -ne "$stub_pass_before" ]]; then
  echo "FAIL: topic mismatch should increment FAIL_COUNT only" >&2
  exit 1
fi
PASS_COUNT="$stub_pass_before"
FAIL_COUNT="$stub_fail_before"

echo "stub: kafka topic pre-create check fails when claim-service logs partition warning"
PHASE0_STUB_KAFKA_LIST=(
  claim.analysis.completed
  claim.analysis.requested
  claim.approved
  claim.approved.DLT
  claim.manual-decision
  claim.rejected
  claim.settled
  claim.submitted
)
PHASE0_STUB_CLAIM_SERVICE_LOGS='exists but has a different partition count: 1 not 3'
stub_pass_before="$PASS_COUNT"
stub_fail_before="$FAIL_COUNT"
check_P2_stack_healthy_locked_topics "17" "stub partition warning" || true
if [[ "$FAIL_COUNT" -ne $((stub_fail_before + 1)) ]]; then
  echo "FAIL: partition-count log line should fail check P2" >&2
  exit 1
fi
PASS_COUNT="$stub_pass_before"
FAIL_COUNT="$stub_fail_before"
PHASE0_STUB_CLAIM_SERVICE_LOGS=''

register_insured() {
  return 0
}

phase0_burst_submit_claim() {
  local jar="$1" index="$2"
  printf '{"id":"burst-claim-%02d"}' "$index" >"${PHASE0_CURL_BODY}"
  return 0
}

claim_id_from_submit() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${PHASE0_CURL_BODY}" 2>/dev/null
}

claim_status() {
  local jar="$1" claim_id="$2"
  if [[ -n "${PHASE0_STUB_BURST_LATE_INDEX:-}" && "$claim_id" == "burst-claim-$(printf '%02d' "${PHASE0_STUB_BURST_LATE_INDEX}")" ]]; then
    printf 'SUBMITTED'
    return 0
  fi
  printf 'APPROVED'
}

phase0_burst_deadline_elapsed() {
  if [[ -n "${PHASE0_STUB_BURST_INSTANT_DEADLINE:-}" ]]; then
    return 0
  fi
  return 1
}

echo "stub: P1 startup burst check passes when all claims reach next state in time"
unset PHASE0_STUB_BURST_LATE_INDEX PHASE0_STUB_BURST_INSTANT_DEADLINE
stub_pass_before="$PASS_COUNT"
stub_fail_before="$FAIL_COUNT"
check_P1_startup_burst "16" "stub P1 burst pass" || true
if [[ "$PASS_COUNT" -ne $((stub_pass_before + 1)) ]]; then
  echo "FAIL: burst pass should increment PASS_COUNT" >&2
  exit 1
fi
PASS_COUNT="$stub_pass_before"
FAIL_COUNT="$stub_fail_before"

echo "stub: P1 startup burst check fails when one claim is late at 60s"
export PHASE0_STUB_BURST_LATE_INDEX=3
export PHASE0_STUB_BURST_INSTANT_DEADLINE=1
stub_pass_before="$PASS_COUNT"
stub_fail_before="$FAIL_COUNT"
check_P1_startup_burst "16" "stub P1 burst late" || true
if [[ "$FAIL_COUNT" -ne $((stub_fail_before + 1)) ]]; then
  echo "FAIL: late burst claim should increment FAIL_COUNT" >&2
  exit 1
fi
PASS_COUNT="$stub_pass_before"
FAIL_COUNT="$stub_fail_before"
unset PHASE0_STUB_BURST_LATE_INDEX PHASE0_STUB_BURST_INSTANT_DEADLINE

echo "stub: one-shot services are omitted from compose health list"
PHASE0_STUB_PS_LINES=$'postgres\trunning\thealthy\nkafka-init\texited\n'
if [[ -n "$(services_not_fully_healthy_list)" ]]; then
  echo "FAIL: kafka-init should be omitted from health list, got: $(services_not_fully_healthy_list)" >&2
  exit 1
fi

echo "stub: running but unhealthy long-running service fails health list"
PHASE0_STUB_PS_LINES=$'claim-service\trunning\tunhealthy'
if ! grep -q 'claim-service' <<<"$(services_not_fully_healthy_list)"; then
  echo "FAIL: unhealthy claim-service should fail health list" >&2
  exit 1
fi
PHASE0_STUB_PS_LINES=$'postgres\trunning\thealthy'

echo "stub: redis claim cache key is scoped to claims::<claimId>"
if [[ "$(redis_claim_cache_key_for_id 'abc-123')" != "claims::abc-123" ]]; then
  echo "FAIL: redis_claim_cache_key_for_id" >&2
  exit 1
fi
PHASE0_STUB_REDIS_KEYS=$'claims::11111111-1111-1111-1111-111111111111\n'
redis_scan_claims_keys() {
  printf '%s\n' "${PHASE0_STUB_REDIS_KEYS}"
}
if redis_scan_contains_claim_id "1111"; then
  echo "FAIL: must not match claim id as substring of another key" >&2
  exit 1
fi
if ! redis_scan_contains_claim_id "11111111-1111-1111-1111-111111111111"; then
  echo "FAIL: expected exact claims::<uuid> match" >&2
  exit 1
fi

echo "stub: P3 runs before-read redis scan before INSURED claim_status on the check-06 claim"
p3_before_read_line="$(grep -n 'log_check_06_redis_scan "before-read"' "${REPO_ROOT}/scripts/phase0-runtime-check.sh" | head -n1 | cut -d: -f1)"
p3_insured_status_line="$(grep -n 'status="$(claim_status "$jar_a" "$claim_id"' "${REPO_ROOT}/scripts/phase0-runtime-check.sh" | head -n1 | cut -d: -f1)"
if [[ -z "$p3_before_read_line" || -z "$p3_insured_status_line" ]]; then
  echo "FAIL: could not locate P3 before-read or insured claim_status lines" >&2
  exit 1
fi
if [[ "$p3_before_read_line" -ge "$p3_insured_status_line" ]]; then
  echo "FAIL: before-read must precede insured claim_status (lines ${p3_before_read_line} vs ${p3_insured_status_line})" >&2
  exit 1
fi

echo "stub: all OK"
