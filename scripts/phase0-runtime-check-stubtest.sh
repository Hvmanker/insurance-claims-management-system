#!/usr/bin/env bash
# Stub tests for phase0-runtime-check (no real Docker). Run from repo root.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export COMPOSE_PROJECT=phase0check

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

echo "stub: all OK"
