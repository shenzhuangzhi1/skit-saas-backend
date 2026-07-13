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
first_clear_count="$(grep -c 'skit_ad_account' "${stub_log}" || true)"
if [[ "${first_clear_count}" -ne 0 ]]; then
  echo "FAIL: normal activation must never infer that advertising credentials need clearing" >&2
  exit 1
fi

run_activation second
second_key="$(sed -n 's/^SKIT_AD_ENCRYPTION_KEY=//p' "${deploy_path}/.env")"
second_clear_count="$(grep -c 'skit_ad_account' "${stub_log}" || true)"
if [[ "${second_key}" != "${first_key}" ]]; then
  echo "FAIL: subsequent activation rotated the persisted AES key" >&2
  exit 1
fi
if [[ "${second_clear_count}" -ne 0 ]]; then
  echo "FAIL: subsequent activation must not clear advertising credentials" >&2
  exit 1
fi

# A supplied, already-valid key without any local marker is a normal restore scenario and must
# not trigger destructive database writes.
restore_path="$(mktemp -d "${temp_root}/restore.XXXXXX")"
cp "${compose_file}" "${restore_path}/docker-compose.prod.yml"
restore_log="${temp_root}/restore.log"
STUB_LOG="${restore_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${restore_path}" IMAGE_NAME="example/backend" IMAGE_TAG="restore" \
  MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
  SKIT_AD_ENCRYPTION_KEY="12345678901234567890123456789012" \
  "${activation_script}" >/dev/null
if grep -q 'skit_ad_account' "${restore_log}"; then
  echo "FAIL: a valid supplied key without a marker triggered credential cleanup" >&2
  exit 1
fi

# Legacy ciphertext cleanup remains available as an explicit, one-shot operator action.
cleanup_log="${temp_root}/cleanup.log"
STUB_LOG="${cleanup_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="cleanup" \
  MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
  SKIT_CLEAR_LEGACY_AD_CREDENTIALS=1 \
  "${activation_script}" >/dev/null
if [[ "$(grep -c 'skit_ad_account' "${cleanup_log}" || true)" -ne 1 ]]; then
  echo "FAIL: explicit legacy credential cleanup did not execute exactly once" >&2
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

if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-cleanup" \
    MYSQL_ROOT_PASSWORD="test-root-password" SKIT_CLEAR_LEGACY_AD_CREDENTIALS="yes" \
    "${activation_script}" >/dev/null 2>&1; then
  echo "FAIL: activation accepted an invalid legacy cleanup switch" >&2
  exit 1
fi

echo "PASS: activation safely persists and reuses the advertising encryption key"
