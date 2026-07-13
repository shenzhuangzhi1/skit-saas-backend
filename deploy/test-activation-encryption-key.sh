#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
activation_script="${repo_root}/deploy/activate-backend.sh"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"
temp_root="$(mktemp -d)"
trap 'rm -rf "${temp_root}"' EXIT

stub_bin="${temp_root}/bin"
deploy_path="${temp_root}/deploy"
stub_log="${temp_root}/docker.log"
mkdir -p "${stub_bin}" "${deploy_path}"
cp "${compose_file}" "${deploy_path}/docker-compose.prod.yml"

cat > "${stub_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%q ' "$@" >> "${STUB_LOG}"
printf '\n' >> "${STUB_LOG}"
exit 0
EOF
cat > "${stub_bin}/curl" <<'EOF'
#!/usr/bin/env bash
printf '200'
EOF
chmod +x "${stub_bin}/docker" "${stub_bin}/curl"

run_activation() {
  STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="$1" \
    MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
    "${activation_script}" >/dev/null
}

run_activation first
first_key="$(sed -n 's/^SKIT_AD_ENCRYPTION_KEY=//p' "${deploy_path}/.env")"
if [[ "${#first_key}" -ne 32 ]]; then
  echo "FAIL: first activation did not persist a 32-byte generated AES key" >&2
  exit 1
fi
if stat -c '%a' "${deploy_path}/.env" >/dev/null 2>&1; then
  env_mode="$(stat -c '%a' "${deploy_path}/.env")"
else
  env_mode="$(stat -f '%Lp' "${deploy_path}/.env")"
fi
if [[ "${env_mode}" != "600" ]]; then
  echo "FAIL: persisted server environment must use mode 0600" >&2
  exit 1
fi
if [[ "$(sed -n 's/^SKIT_AD_ENCRYPTION_KEY_BOOTSTRAPPED=//p' "${deploy_path}/.env")" != "1" ]]; then
  echo "FAIL: first activation did not persist the credential-bootstrap marker" >&2
  exit 1
fi
first_clear_count="$(grep -c 'skit_ad_account' "${stub_log}")"
if [[ "${first_clear_count}" -ne 1 ]]; then
  echo "FAIL: first activation must clear legacy encrypted credentials exactly once" >&2
  exit 1
fi

run_activation second
second_key="$(sed -n 's/^SKIT_AD_ENCRYPTION_KEY=//p' "${deploy_path}/.env")"
second_clear_count="$(grep -c 'skit_ad_account' "${stub_log}")"
if [[ "${second_key}" != "${first_key}" ]]; then
  echo "FAIL: subsequent activation rotated the persisted AES key" >&2
  exit 1
fi
if [[ "${second_clear_count}" -ne "${first_clear_count}" ]]; then
  echo "FAIL: subsequent activation repeated the one-time credential cleanup" >&2
  exit 1
fi

assert_invalid_key() {
  key="$1"
  invalid_path="$(mktemp -d "${temp_root}/invalid.XXXXXX")"
  cp "${compose_file}" "${invalid_path}/docker-compose.prod.yml"
  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${invalid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid" \
      MYSQL_ROOT_PASSWORD="test-root-password" SKIT_AD_ENCRYPTION_KEY="${key}" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted invalid encryption key length or characters" >&2
    exit 1
  fi
}

assert_invalid_key '123456789012345'
assert_invalid_key '12345678901234567'
assert_invalid_key '1234567890123456789012345'
assert_invalid_key '123456789012345678901234567890123'
assert_invalid_key '123456789012345$'

echo "PASS: activation bootstraps, persists, and reuses the advertising encryption key"
