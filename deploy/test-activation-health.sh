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
  deploy_path="${temp_root}/${case_name}"
  mkdir -p "${deploy_path}"
  cp "${compose_file}" "${deploy_path}/docker-compose.prod.yml"
  CURL_CODE="${code}" CURL_BODY="${body}" PATH="${stub_bin}:${PATH}" \
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

echo "PASS: activation requires an exact HTTP 200 JSON health status UP"
