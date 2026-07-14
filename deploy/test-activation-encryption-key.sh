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
first_credential_key="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY=//p' "${deploy_path}/.env")"
first_credential_key_id="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY_ID=//p' "${deploy_path}/.env")"
first_session_token_key="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY=//p' "${deploy_path}/.env")"
first_session_token_key_version="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY_VERSION=//p' "${deploy_path}/.env")"
if [[ "${#first_key}" -ne 32 ]]; then
  echo "FAIL: first activation did not persist a 32-byte generated AES key" >&2
  exit 1
fi
if [[ "${#first_credential_key}" -ne 32 ]]; then
  echo "FAIL: first activation did not persist a dedicated 32-byte credential key" >&2
  exit 1
fi
if [[ "${first_credential_key}" == "${first_key}" ]]; then
  echo "FAIL: credential envelopes must not reuse the legacy field-encryption key" >&2
  exit 1
fi
if [[ "${first_credential_key_id}" != "primary" ]]; then
  echo "FAIL: first activation did not persist the stable default credential key id" >&2
  exit 1
fi
if [[ "${#first_session_token_key}" -lt 32 ||
      ! "${first_session_token_key}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
  echo "FAIL: first activation did not persist a safe session-token key of at least 32 characters" >&2
  exit 1
fi
if [[ "${first_session_token_key}" == "${first_key}" ||
      "${first_session_token_key}" == "${first_credential_key}" ]]; then
  echo "FAIL: session-token signing must not reuse an encryption or credential key" >&2
  exit 1
fi
if [[ "${first_session_token_key_version}" != "1" ]]; then
  echo "FAIL: first activation did not persist the default positive session-token key version" >&2
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
second_credential_key="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY=//p' "${deploy_path}/.env")"
second_credential_key_id="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY_ID=//p' "${deploy_path}/.env")"
second_session_token_key="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY=//p' "${deploy_path}/.env")"
second_session_token_key_version="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY_VERSION=//p' "${deploy_path}/.env")"
second_clear_count="$(grep -c 'skit_ad_account' "${stub_log}" || true)"
if [[ "${second_key}" != "${first_key}" ]]; then
  echo "FAIL: subsequent activation rotated the persisted AES key" >&2
  exit 1
fi
if [[ "${second_credential_key}" != "${first_credential_key}" ||
      "${second_credential_key_id}" != "${first_credential_key_id}" ]]; then
  echo "FAIL: subsequent activation rotated the persisted credential key or id" >&2
  exit 1
fi
if [[ "${second_session_token_key}" != "${first_session_token_key}" ||
      "${second_session_token_key_version}" != "${first_session_token_key_version}" ]]; then
  echo "FAIL: subsequent activation rotated the persisted session-token key or version" >&2
  exit 1
fi
if [[ "${second_clear_count}" -ne 0 ]]; then
  echo "FAIL: subsequent activation must not clear advertising credentials" >&2
  exit 1
fi

# Release-time server configuration must not silently rotate an already-persisted credential key.
{
  printf 'SKIT_AD_CREDENTIAL_KEY=%s\n' 'override-credential-key-00000001'
  printf 'SKIT_AD_CREDENTIAL_KEY_ID=%s\n' 'override-key'
  printf 'SKIT_AD_SESSION_TOKEN_KEY=%s\n' 'override-session-token-key-00001'
  printf 'SKIT_AD_SESSION_TOKEN_KEY_VERSION=%s\n' '2'
} > "${deploy_path}/server.env"
run_activation attempted-override
rm "${deploy_path}/server.env"
override_key="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY=//p' "${deploy_path}/.env")"
override_key_id="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY_ID=//p' "${deploy_path}/.env")"
override_session_token_key="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY=//p' "${deploy_path}/.env")"
override_session_token_key_version="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY_VERSION=//p' "${deploy_path}/.env")"
if [[ "${override_key}" != "${first_credential_key}" ||
      "${override_key_id}" != "${first_credential_key_id}" ]]; then
  echo "FAIL: release-time server configuration overwrote persisted credential key material" >&2
  exit 1
fi
if [[ "${override_session_token_key}" != "${first_session_token_key}" ||
      "${override_session_token_key_version}" != "${first_session_token_key_version}" ]]; then
  echo "FAIL: release-time server configuration overwrote persisted session-token key material" >&2
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
  SKIT_AD_CREDENTIAL_KEY="abcdefghijklmnopqrstuvwx12345678" \
  SKIT_AD_CREDENTIAL_KEY_ID="restore-key" \
  SKIT_AD_SESSION_TOKEN_KEY="restore-session-token-key-00000001" \
  SKIT_AD_SESSION_TOKEN_KEY_VERSION="7" \
  "${activation_script}" >/dev/null
if grep -q 'skit_ad_account' "${restore_log}"; then
  echo "FAIL: a valid supplied key without a marker triggered credential cleanup" >&2
  exit 1
fi
if ! grep -q '^SKIT_AD_SESSION_TOKEN_KEY=restore-session-token-key-00000001$' "${restore_path}/.env" ||
   ! grep -q '^SKIT_AD_SESSION_TOKEN_KEY_VERSION=7$' "${restore_path}/.env"; then
  echo "FAIL: supplied session-token key material or version was not persisted exactly" >&2
  exit 1
fi
if ! grep -q '^SKIT_AD_CREDENTIAL_KEY=abcdefghijklmnopqrstuvwx12345678$' "${restore_path}/.env" ||
   ! grep -q '^SKIT_AD_CREDENTIAL_KEY_ID=restore-key$' "${restore_path}/.env"; then
  echo "FAIL: supplied credential key material or key id was not persisted exactly" >&2
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

assert_invalid_credential_key() {
  key="$1"
  invalid_path="$(mktemp -d "${temp_root}/invalid-credential.XXXXXX")"
  cp "${compose_file}" "${invalid_path}/docker-compose.prod.yml"
  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${invalid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-credential" \
      MYSQL_ROOT_PASSWORD="test-root-password" \
      SKIT_AD_CREDENTIAL_KEY="${key}" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted invalid credential key length or characters" >&2
    exit 1
  fi
}

assert_invalid_credential_key '123456789012345'
assert_invalid_credential_key '12345678901234567'
assert_invalid_credential_key '1234567890123456789012345'
assert_invalid_credential_key '123456789012345678901234567890123'
assert_invalid_credential_key '123456789012345$'

assert_invalid_session_token_key() {
  key="$1"
  invalid_path="$(mktemp -d "${temp_root}/invalid-session-token.XXXXXX")"
  cp "${compose_file}" "${invalid_path}/docker-compose.prod.yml"
  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${invalid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-session-token" \
      MYSQL_ROOT_PASSWORD="test-root-password" \
      SKIT_AD_SESSION_TOKEN_KEY="${key}" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted an unsafe or undersized session-token key" >&2
    exit 1
  fi
}

assert_invalid_session_token_key '1234567890123456789012345678901'
assert_invalid_session_token_key '1234567890123456789012345678901$'

assert_invalid_session_token_version() {
  version="$1"
  invalid_path="$(mktemp -d "${temp_root}/invalid-session-version.XXXXXX")"
  cp "${compose_file}" "${invalid_path}/docker-compose.prod.yml"
  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${invalid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-session-version" \
      MYSQL_ROOT_PASSWORD="test-root-password" \
      SKIT_AD_SESSION_TOKEN_KEY="valid-session-token-key-0000000001" \
      SKIT_AD_SESSION_TOKEN_KEY_VERSION="${version}" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted a non-positive or out-of-range session-token key version" >&2
    exit 1
  fi
}

assert_valid_session_token_version() {
  version="$1"
  valid_path="$(mktemp -d "${temp_root}/valid-session-version.XXXXXX")"
  cp "${compose_file}" "${valid_path}/docker-compose.prod.yml"
  STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${valid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="valid-session-version" \
    MYSQL_ROOT_PASSWORD="test-root-password" \
    SKIT_AD_SESSION_TOKEN_KEY="valid-session-token-key-0000000001" \
    SKIT_AD_SESSION_TOKEN_KEY_VERSION="${version}" \
    "${activation_script}" >/dev/null
  if ! grep -q "^SKIT_AD_SESSION_TOKEN_KEY_VERSION=${version}$" "${valid_path}/.env"; then
    echo "FAIL: activation did not persist a valid 32-bit session-token key version" >&2
    exit 1
  fi
}

assert_valid_session_token_version '2147483647'

assert_invalid_session_token_version '0'
assert_invalid_session_token_version '-1'
assert_invalid_session_token_version 'one'
assert_invalid_session_token_version '2147483648'

assert_reused_session_token_key() {
  collision_target="$1"
  invalid_path="$(mktemp -d "${temp_root}/reused-session-token.XXXXXX")"
  cp "${compose_file}" "${invalid_path}/docker-compose.prod.yml"
  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${invalid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="reused-session-token" \
      MYSQL_ROOT_PASSWORD="test-root-password" \
      SKIT_AD_ENCRYPTION_KEY="12345678901234567890123456789012" \
      SKIT_AD_CREDENTIAL_KEY="abcdefghijklmnopqrstuvwx12345678" \
      SKIT_AD_SESSION_TOKEN_KEY="${collision_target}" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted a session-token key reused from another key purpose" >&2
    exit 1
  fi
}

assert_reused_session_token_key '12345678901234567890123456789012'
assert_reused_session_token_key 'abcdefghijklmnopqrstuvwx12345678'

invalid_id_path="$(mktemp -d "${temp_root}/invalid-id.XXXXXX")"
cp "${compose_file}" "${invalid_id_path}/docker-compose.prod.yml"
if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${invalid_id_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-id" \
    MYSQL_ROOT_PASSWORD="test-root-password" SKIT_AD_CREDENTIAL_KEY_ID='bad key id' \
    "${activation_script}" >/dev/null 2>&1; then
  echo "FAIL: activation accepted an unsafe credential key id" >&2
  exit 1
fi

if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-cleanup" \
    MYSQL_ROOT_PASSWORD="test-root-password" SKIT_CLEAR_LEGACY_AD_CREDENTIALS="yes" \
    "${activation_script}" >/dev/null 2>&1; then
  echo "FAIL: activation accepted an invalid legacy cleanup switch" >&2
  exit 1
fi

echo "PASS: activation safely persists and reuses independent advertising keys"
