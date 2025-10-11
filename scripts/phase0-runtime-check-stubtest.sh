#!/usr/bin/env bash
# Stub tests for phase0-runtime-check (no real Docker). Run from repo root.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export COMPOSE_PROJECT=phase0check
PHASE0_STUB_ORIGINAL_PATH="${PATH}"
export PHASE0_ORIGINAL_PATH="${PHASE0_STUB_ORIGINAL_PATH}"

STUB_BIN="$(mktemp -d)"
trap 'rm -rf "$STUB_BIN"' EXIT

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

echo "stub: collect_demo_password_env_names_from_fixtures finds 9 persona passwords"
collect_demo_password_env_names_from_fixtures "$(persona_fixtures_dir)"
if ((${#DEMO_PASSWORD_ENV_NAMES[@]} != 9)); then
  echo "FAIL: expected 9 demo password env names, got ${#DEMO_PASSWORD_ENV_NAMES[@]}" >&2
  exit 1
fi

echo "stub: empty fixtures dir exits 2 with zero password env names"
empty_fixtures="$(mktemp -d)"
set +e
collect_demo_password_env_names_from_fixtures "$empty_fixtures" >/dev/null 2>&1
zero_rc=$?
set -e
if [[ "$zero_rc" -ne 2 ]]; then
  echo "FAIL: empty fixtures dir should exit 2, got ${zero_rc}" >&2
  exit 1
fi

demo_env_err="$(mktemp)"
trap 'rm -rf "$STUB_BIN"; rm -f "$demo_env_err"' EXIT

echo "stub: require_demo_env_vars_when_enabled fails when a demo password is unset"
export SPRING_PROFILES_ACTIVE=demo
collect_demo_password_env_names_from_fixtures "$(persona_fixtures_dir)"
for v in "${DEMO_PASSWORD_ENV_NAMES[@]}"; do
  export "${v}=stub-value-not-in-output"
done
unset DEMO_SIU_PASSWORD
set +e
( require_demo_env_vars_when_enabled ) 2>"$demo_env_err"
unset_rc=$?
set -e
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
set +e
( require_demo_env_vars_when_enabled ) 2>"$demo_env_err"
empty_rc=$?
set -e
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
set +e
( require_demo_env_vars_when_enabled )
all_ok_rc=$?
set -e
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
rm -f "$left_tmp" "$LOG_FILE"

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

export PHASE0_COMPOSE_CONFIG_ENV_FILE="${REPO_ROOT}/.env.example"
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
  echo "stub: compose CLI rejects uppercase project name (compose-only check)"
  set +e
  phase0_validate_compose_project_name_compose_only "$bad_name_upper" >/dev/null 2>&1
  compose_upper_rc=$?
  set -e
  if [[ "$compose_upper_rc" -ne 2 ]]; then
    echo "FAIL: compose-only uppercase validation should return 2, got ${compose_upper_rc}" >&2
    exit 1
  fi
fi

echo "stub: PHASE0_NO_TEARDOWN compose project names and stop-before-up"
DOCKER_STUB_LOG="$(mktemp)"
export DOCKER_STUB_LOG
cat > "${STUB_BIN}/docker" <<'DOCKERSTUB'
#!/usr/bin/env bash
printf '%s\n' "docker $*" >> "${DOCKER_STUB_LOG}"
if [[ "${1:-}" == "compose" ]]; then
  exit 0
fi
exit 0
DOCKERSTUB
chmod +x "${STUB_BIN}/docker"
export PATH="${STUB_BIN}:${PATH}"
export COMPOSE_PROJECT=phase0check
PHASE0_STOPPED_PROJECTS=()
PHASE0_USED_PROJECTS=()
PHASE0_CREATED_PROJECTS=()
PHASE0_ACTIVE_COMPOSE_PROJECT=""
PHASE0_COMPOSE_RUN_TS="20261011t120000z"
PHASE0_PROJECT_STARTUP="$(phase0_compose_project_for_phase startup)"
PHASE0_PROJECT_C04="$(phase0_compose_project_for_phase c04)"
PHASE0_PROJECT_C05="$(phase0_compose_project_for_phase c05)"
phase0_compose_stop_project "${REQUIRED_COMPOSE_PROJECT}"
phase0_switch_compose_project "${PHASE0_PROJECT_STARTUP}"
phase0_prepare_fresh_compose_stack "c04"
if ! grep -qE 'compose -p phase0check .* stop' "$DOCKER_STUB_LOG"; then
  echo "FAIL: expected compose stop before fresh stack" >&2
  exit 1
fi
if grep -qE 'compose.*(down|rm)' "$DOCKER_STUB_LOG"; then
  echo "FAIL: PHASE0_NO_TEARDOWN must not invoke compose down or rm" >&2
  exit 1
fi
stop_count="$(grep -c 'compose -p .* stop' "$DOCKER_STUB_LOG" || true)"
if [[ "$stop_count" -lt 2 ]]; then
  echo "FAIL: expected at least two compose stop calls, got ${stop_count}" >&2
  exit 1
fi

echo "stub: PHASE0_NO_TEARDOWN does not stop projects outside allowlist"
: >"$DOCKER_STUB_LOG"
phase0_compose_stop_project "phase0check-not-created-this-run"
if grep -q 'phase0check-not-created-this-run' "$DOCKER_STUB_LOG"; then
  echo "FAIL: must not stop projects not created this run" >&2
  exit 1
fi

echo "stub: PHASE0_NO_TEARDOWN guard accepts phase0check- suffix"
set +e
( unset PHASE0_NO_TEARDOWN; export COMPOSE_PROJECT=phase0check-extra; require_compose_project_guard ) 2>/dev/null
bad_rc=$?
( export PHASE0_NO_TEARDOWN=1; export COMPOSE_PROJECT=phase0check-extra; require_compose_project_guard )
good_rc=$?
set -e
if [[ "$bad_rc" -eq 0 ]]; then
  echo "FAIL: phase0check-extra should fail guard without PHASE0_NO_TEARDOWN" >&2
  exit 1
fi
if [[ "$good_rc" -ne 0 ]]; then
  echo "FAIL: phase0check-extra should pass guard with PHASE0_NO_TEARDOWN=1" >&2
  exit 1
fi

unset PHASE0_NO_TEARDOWN
export COMPOSE_PROJECT=phase0check
rm -f "$DOCKER_STUB_LOG"

echo "stub: all OK"
