#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
activation_script="${repo_root}/deploy/activate-backend.sh"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"
temp_root="$(mktemp -d)"
trap 'rm -rf "${temp_root}"' EXIT

stub_bin="${temp_root}/bin"
mkdir -p "${stub_bin}"

cat > "${stub_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"information_schema.TABLES"* ]]; then
  if [[ -n "${STUB_QUARTZ_STATE_FILE:-}" && -f "${STUB_QUARTZ_STATE_FILE}" ]]; then
    printf '%s\n' "${QUARTZ_STATE_AFTER_IMPORT:-11:79:11}"
  else
    printf '%s\n' "${QUARTZ_SCHEMA_STATE:-11:79:11}"
  fi
  exit 0
fi
if [[ "$*" == *"mysql-in-container"* && "$*" != *" -e "* ]]; then
  : > "${STUB_QUARTZ_STATE_FILE}"
  exit 0
fi
if [[ "$*" == *"inspect --format"* ]]; then
  printf '%s\n' "${BACKEND_INSPECT_STATE:-0 running}"
  exit 0
fi
if [[ " $* " == *" logs "* ]]; then
  printf '%s' "${BACKEND_LOG_BODY:-}"
  exit "${BACKEND_LOG_EXIT_CODE:-0}"
fi
exit 0
EOF
cat > "${stub_bin}/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "${stub_bin}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
output_file=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    -o)
      output_file="$2"
      shift 2
      ;;
    -w)
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done
if [[ -n "${output_file}" ]]; then
  printf '%s' "${CURL_BODY}" > "${output_file}"
else
  printf '%s' "${CURL_BODY}"
fi
printf '%s' "${CURL_CODE}"
EOF
chmod +x "${stub_bin}/docker" "${stub_bin}/sleep" "${stub_bin}/curl"

run_activation() {
  case_name="$1"
  code="$2"
  body="$3"
  quartz_schema_state="${4:-11:79:11}"
  backend_inspect_state="${5:-0 running}"
  backend_log_body="${6:-}"
  quartz_state_after_import="${7:-11:79:11}"
  backend_log_exit_code="${8:-0}"
  deploy_path="${temp_root}/${case_name}"
  quartz_state_file="${temp_root}/${case_name}.quartz-imported"
  rm -f "${quartz_state_file}"
  mkdir -p "${deploy_path}/mysql/init"
  cp "${compose_file}" "${deploy_path}/docker-compose.prod.yml"
  if [[ "${case_name}" != "missing-quartz-file" ]]; then
    : > "${deploy_path}/mysql/init/quartz.sql"
  fi
  CURL_CODE="${code}" CURL_BODY="${body}" QUARTZ_SCHEMA_STATE="${quartz_schema_state}" \
    QUARTZ_STATE_AFTER_IMPORT="${quartz_state_after_import}" \
    STUB_QUARTZ_STATE_FILE="${quartz_state_file}" \
    BACKEND_INSPECT_STATE="${backend_inspect_state}" BACKEND_LOG_BODY="${backend_log_body}" \
    BACKEND_LOG_EXIT_CODE="${backend_log_exit_code}" \
    PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="health-test" \
    MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
    SKIT_AD_ENCRYPTION_KEY="12345678901234567890123456789012" \
    SKIT_AD_CREDENTIAL_KEY="abcdefghijklmnopqrstuvwx12345678" \
    SKIT_AD_CREDENTIAL_KEY_ID="primary" \
    SKIT_AD_SESSION_TOKEN_KEY="valid-session-token-key-0000000001" \
    SKIT_AD_SESSION_TOKEN_KEY_VERSION="1" \
    "${activation_script}" >/dev/null 2>&1
}

run_activation healthy 200 '{"status":"UP"}'
run_activation repair-missing-quartz 200 '{"status":"UP"}' '0:0:0'

if run_activation partial-quartz 200 '{"status":"UP"}' '5:20:5'; then
  echo "FAIL: activation accepted a partially corrupt Quartz schema" >&2
  exit 1
fi
if run_activation missing-quartz-file 200 '{"status":"UP"}' '0:0:0'; then
  echo "FAIL: activation accepted a missing Quartz schema without its repair artifact" >&2
  exit 1
fi
if run_activation bad-quartz-repair 200 '{"status":"UP"}' \
    '0:0:0' '0 running' '' '5:20:5'; then
  echo "FAIL: activation accepted an incomplete Quartz schema after repair" >&2
  exit 1
fi
if ! run_activation restarted-backend 200 '{"status":"UP"}' '11:79:11' '1 running'; then
  echo "FAIL: activation rejected a backend after a bounded startup dependency restart" >&2
  exit 1
fi
if run_activation too-many-restarts 200 '{"status":"UP"}' '11:79:11' '4 running'; then
  echo "FAIL: activation accepted a backend that exceeded the startup restart budget" >&2
  exit 1
fi
if run_activation quartz-persistence-error 200 '{"status":"UP"}' \
    '11:79:11' '0 running' 'org.quartz.JobPersistenceException: missing QRTZ_TRIGGERS'; then
  echo "FAIL: activation accepted an HTTP-healthy backend with broken Quartz persistence" >&2
  exit 1
fi
if run_activation unreadable-backend-logs 200 '{"status":"UP"}' \
    '11:79:11' '0 running' '' '11:79:11' '42'; then
  echo "FAIL: activation accepted a backend whose startup logs could not be read" >&2
  exit 1
fi

for rejected_case in \
  'wrong-code|204|{"status":"UP"}' \
  'down|200|{"status":"DOWN"}' \
  'malformed|200|prefix{"status":"UP"}' \
  'wrong-shape|200|{"components":{"status":"UP"}}'; do
  IFS='|' read -r case_name code body <<<"${rejected_case}"
  if run_activation "${case_name}" "${code}" "${body}"; then
    echo "FAIL: activation accepted unhealthy response ${case_name}" >&2
    exit 1
  fi
done

echo "PASS: activation requires complete Quartz state, bounded restarts, clean logs, and exact health"
