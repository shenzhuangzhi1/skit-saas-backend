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
if [[ -n "${EXPECTED_REMOVED_SERVER_ENV:-}" && -e "${EXPECTED_REMOVED_SERVER_ENV}" ]]; then
  echo "FAIL: staged backend server.env still exists at first child process" >&2
  exit 97
fi
for argument in "$@"; do
  if [[ -n "${SECRET_ARG_SENTINEL:-}" && "${argument}" == *"${SECRET_ARG_SENTINEL}"* ]]; then
    echo "FAIL: backend child argv contains a staged deployment secret" >&2
    exit 98
  fi
  if [[ -n "${MYSQL_SECRET_ARG_SENTINEL:-}" &&
        "${argument}" == *"${MYSQL_SECRET_ARG_SENTINEL}"* ]]; then
    echo "FAIL: backend child argv contains the MySQL root password" >&2
    exit 99
  fi
done
printf '%q ' "$@" >> "${STUB_LOG}"
printf '\n' >> "${STUB_LOG}"
if [[ "$*" == *"information_schema.TABLES"* ]]; then
  printf '11:79:11\n'
fi
if [[ "$*" == *"inspect --format"* ]]; then
  printf '0 running\n'
fi
exit 0
EOF
cat > "${stub_bin}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
output_file=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    -o) output_file="$2"; shift 2 ;;
    -w) shift 2 ;;
    *) shift ;;
  esac
done
printf '%s' '{"status":"UP"}' > "${output_file}"
printf '200'
EOF
cat > "${stub_bin}/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${stub_bin}/docker" "${stub_bin}/curl" "${stub_bin}/sleep"
export MYSQL_SECRET_ARG_SENTINEL="test-root-password"

run_activation() {
  activation_output="${temp_root}/activation-$1.log"
  if ! STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="$1" \
      MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
      "${activation_script}" >"${activation_output}" 2>&1; then
    cat "${activation_output}" >&2
    return 1
  fi
}

run_activation first
first_key="$(sed -n 's/^SKIT_AD_ENCRYPTION_KEY=//p' "${deploy_path}/.env")"
first_credential_key="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY=//p' "${deploy_path}/.env")"
first_credential_key_id="$(sed -n 's/^SKIT_AD_CREDENTIAL_KEY_ID=//p' "${deploy_path}/.env")"
first_session_token_key="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY=//p' "${deploy_path}/.env")"
first_session_token_key_version="$(sed -n 's/^SKIT_AD_SESSION_TOKEN_KEY_VERSION=//p' "${deploy_path}/.env")"
first_callback_public_base_url="$(sed -n 's/^SKIT_AD_CALLBACK_PUBLIC_BASE_URL=//p' "${deploy_path}/.env")"
first_api_encrypt_enabled="$(sed -n 's/^YUDAO_API_ENCRYPT_ENABLED=//p' "${deploy_path}/.env")"
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
if [[ "${first_callback_public_base_url}" != "http://localhost:48080/app-api" ]]; then
  echo "FAIL: activation did not persist the normalized trusted callback public base URL" >&2
  exit 1
fi
if [[ "${first_api_encrypt_enabled}" != "false" ]]; then
  echo "FAIL: activation did not keep the unused framework API encryption disabled by default" >&2
  exit 1
fi
if grep -Eq '^YUDAO_API_ENCRYPT_(REQUEST|RESPONSE)_KEY=' "${deploy_path}/.env"; then
  echo "FAIL: disabled API encryption persisted unnecessary key material" >&2
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
keyring_file="${deploy_path}/ad-keyring.properties"
if [[ ! -f "${keyring_file}" ]]; then
  echo "FAIL: activation did not provision the retained-key configuration file" >&2
  exit 1
fi
if stat -c '%a' "${keyring_file}" >/dev/null 2>&1; then
  keyring_mode="$(stat -c '%a' "${keyring_file}")"
else
  keyring_mode="$(stat -f '%Lp' "${keyring_file}")"
fi
if [[ "${keyring_mode}" != "600" ]]; then
  echo "FAIL: retained-key configuration must use mode 0600" >&2
  exit 1
fi
if [[ -s "${keyring_file}" ]]; then
  echo "FAIL: first activation invented retained advertising keys" >&2
  exit 1
fi
# Schema diagnostics may legitimately mention the account table. Only the explicit
# destructive cleanup statement counts as credential clearing here.
first_clear_count="$(grep -c 'UPDATE\\ skit_ad_account\\ SET\\ app_key' "${stub_log}" || true)"
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
second_clear_count="$(grep -c 'UPDATE\\ skit_ad_account\\ SET\\ app_key' "${stub_log}" || true)"
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

# Operators may seed retained keys once as base64-encoded Spring properties. The decoded file is
# persisted with mode 0600 and routine releases must leave it byte-for-byte unchanged.
keyring_seed_path="$(mktemp -d "${temp_root}/keyring-seed.XXXXXX")"
cp "${compose_file}" "${keyring_seed_path}/docker-compose.prod.yml"
keyring_plaintext="${temp_root}/keyring.properties"
cat > "${keyring_plaintext}" <<'EOF'
skit.ad.credential-encryption.keys.previous=previous-credential-key-00000001
skit.ad.session-token.keys.7=previous-session-token-key-0000001
EOF
keyring_base64="$(base64 < "${keyring_plaintext}" | tr -d '\r\n')"
STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${keyring_seed_path}" IMAGE_NAME="example/backend" IMAGE_TAG="keyring-seed" \
  MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
  SKIT_AD_RETAINED_KEYRING_BASE64="${keyring_base64}" \
  "${activation_script}" >/dev/null
cmp "${keyring_plaintext}" "${keyring_seed_path}/ad-keyring.properties"
keyring_checksum="$(shasum -a 256 "${keyring_seed_path}/ad-keyring.properties" | awk '{print $1}')"
STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${keyring_seed_path}" IMAGE_NAME="example/backend" IMAGE_TAG="ordinary-release" \
  MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
  "${activation_script}" >/dev/null
if [[ "$(shasum -a 256 "${keyring_seed_path}/ad-keyring.properties" | awk '{print $1}')" != \
      "${keyring_checksum}" ]]; then
  echo "FAIL: ordinary release changed the retained advertising keyring" >&2
  exit 1
fi

conflicting_plaintext="${temp_root}/conflicting-keyring.properties"
cat > "${conflicting_plaintext}" <<'EOF'
skit.ad.credential-encryption.keys.previous=other-old-credential-key-0000001
EOF
conflicting_base64="$(base64 < "${conflicting_plaintext}" | tr -d '\r\n')"
if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${keyring_seed_path}" IMAGE_NAME="example/backend" IMAGE_TAG="keyring-conflict" \
    MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
    SKIT_AD_RETAINED_KEYRING_BASE64="${conflicting_base64}" \
    "${activation_script}" >/dev/null 2>&1; then
  echo "FAIL: activation silently replaced an existing retained-key keyring" >&2
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
if [[ -e "${deploy_path}/server.env" ]]; then
  echo "FAIL: activation retained the uploaded server.env after use" >&2
  exit 1
fi
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

assert_staged_secret_cleanup_on_preflight_failure() {
  scenario="$1"
  failed_deploy_path="$(mktemp -d "${temp_root}/release-preflight-${scenario}.XXXXXX")"
  failed_release_path="${failed_deploy_path}/releases/backend-${scenario}"
  mkdir -p "${failed_release_path}"
  printf 'MYSQL_DATABASE=skit_saas\n' > "${failed_release_path}/server.env"

  case "${scenario}" in
    missing-compose)
      : > "${failed_release_path}/ruoyi-vue-pro.sql"
      : > "${failed_release_path}/skit-saas.sql"
      ;;
    missing-sql)
      cp "${compose_file}" "${failed_release_path}/docker-compose.prod.yml"
      : > "${failed_release_path}/ruoyi-vue-pro.sql"
      ;;
    symlinked-compose)
      ln -s "${compose_file}" "${failed_release_path}/docker-compose.prod.yml"
      : > "${failed_release_path}/ruoyi-vue-pro.sql"
      : > "${failed_release_path}/skit-saas.sql"
      ;;
    *)
      echo "FAIL: unsupported staged-secret cleanup scenario ${scenario}" >&2
      exit 1
      ;;
  esac

  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${failed_deploy_path}" RELEASE_BUNDLE_PATH="releases/backend-${scenario}" \
      IMAGE_NAME="example/backend" IMAGE_TAG="${scenario}" \
      MYSQL_ROOT_PASSWORD="test-root-password" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted unsafe staged release ${scenario}" >&2
    exit 1
  fi
  if [[ -e "${failed_release_path}/server.env" || -L "${failed_release_path}/server.env" ]]; then
    echo "FAIL: activation retained server.env after ${scenario} preflight failure" >&2
    exit 1
  fi
}

assert_staged_secret_cleanup_on_preflight_failure missing-compose
assert_staged_secret_cleanup_on_preflight_failure missing-sql
assert_staged_secret_cleanup_on_preflight_failure symlinked-compose

# A valid staged release must consume and unlink its 0600 environment before the first child
# process. Registry and sudo credentials are allowed only as shell values/stdin, never argv.
staged_release_id="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-67890-3"
staged_deploy_path="$(mktemp -d "${temp_root}/release-secrets.XXXXXX")"
staged_release_path="${staged_deploy_path}/releases/backend-${staged_release_id}"
mkdir -p "${staged_release_path}"
cp "${compose_file}" "${staged_release_path}/docker-compose.prod.yml"
: > "${staged_release_path}/ruoyi-vue-pro.sql"
: > "${staged_release_path}/skit-saas.sql"
: > "${staged_release_path}/quartz.sql"
secret_arg_sentinel="backend-secret-argv-sentinel"
{
  printf 'MYSQL_ROOT_PASSWORD=%q\n' 'test-root-password'
  printf 'MYSQL_DATABASE=%q\n' 'skit_saas'
  printf 'GHCR_USERNAME=%q\n' 'example'
  printf 'GHCR_TOKEN=%q\n' "${secret_arg_sentinel}"
  printf 'SUDO_PASSWORD=%q\n' 'backend-sudo-stdin-sentinel'
} > "${staged_release_path}/server.env"
chmod 600 "${staged_release_path}/server.env"
staged_log="${temp_root}/staged-release.log"
if ! STUB_LOG="${staged_log}" PATH="${stub_bin}:${PATH}" \
    EXPECTED_REMOVED_SERVER_ENV="${staged_release_path}/server.env" \
    SECRET_ARG_SENTINEL="${secret_arg_sentinel}" \
    DEPLOY_PATH="${staged_deploy_path}" \
    RELEASE_BUNDLE_PATH="releases/backend-${staged_release_id}" \
    IMAGE_NAME="example/backend" IMAGE_TAG="staged-release" \
    "${activation_script}" >/dev/null; then
  echo "FAIL: valid staged backend release did not consume secrets safely" >&2
  exit 1
fi
if [[ -e "${staged_release_path}/server.env" || -L "${staged_release_path}/server.env" ]]; then
  echo "FAIL: valid staged backend release retained server.env" >&2
  exit 1
fi
if grep -Fq "${secret_arg_sentinel}" "${staged_log}"; then
  echo "FAIL: backend deployment secret reached child argv logging" >&2
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
if grep -q 'UPDATE\\ skit_ad_account\\ SET\\ app_key' "${restore_log}"; then
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

# Optional framework API encryption stays disabled and keyless by default. When an operator opts
# in, activation validates and persists one coordinated, independent AES key pair before Docker.
api_encrypt_path="$(mktemp -d "${temp_root}/api-encrypt.XXXXXX")"
cp "${compose_file}" "${api_encrypt_path}/docker-compose.prod.yml"
STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${api_encrypt_path}" IMAGE_NAME="example/backend" IMAGE_TAG="api-encrypt" \
  MYSQL_ROOT_PASSWORD="test-root-password" \
  YUDAO_API_ENCRYPT_ENABLED=true \
  YUDAO_API_ENCRYPT_REQUEST_KEY='request-key-00000000000000000001' \
  YUDAO_API_ENCRYPT_RESPONSE_KEY='response-key-0000000000000000001' \
  "${activation_script}" >/dev/null
for expected_api_setting in \
    'YUDAO_API_ENCRYPT_ENABLED=true' \
    'YUDAO_API_ENCRYPT_REQUEST_KEY=request-key-00000000000000000001' \
    'YUDAO_API_ENCRYPT_RESPONSE_KEY=response-key-0000000000000000001'; do
  if ! grep -Fqx "${expected_api_setting}" "${api_encrypt_path}/.env"; then
    echo "FAIL: activation did not persist ${expected_api_setting%%=*}" >&2
    exit 1
  fi
done
STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${api_encrypt_path}" IMAGE_NAME="example/backend" IMAGE_TAG="api-encrypt-reuse" \
  MYSQL_ROOT_PASSWORD="test-root-password" \
  "${activation_script}" >/dev/null

api_encrypt_disable_env="${api_encrypt_path}/disable-api-encryption.env"
printf '%s\n' 'YUDAO_API_ENCRYPT_ENABLED=false' > "${api_encrypt_disable_env}"
chmod 600 "${api_encrypt_disable_env}"
STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${api_encrypt_path}" IMAGE_NAME="example/backend" IMAGE_TAG="api-encrypt-disabled" \
  MYSQL_ROOT_PASSWORD="test-root-password" SERVER_ENV_FILE="${api_encrypt_disable_env}" \
  "${activation_script}" >/dev/null
if ! grep -Fqx 'YUDAO_API_ENCRYPT_ENABLED=false' "${api_encrypt_path}/.env"; then
  echo "FAIL: activation did not persist an explicit API-encryption disable" >&2
  exit 1
fi
if grep -Eq '^YUDAO_API_ENCRYPT_(REQUEST|RESPONSE)_KEY=' "${api_encrypt_path}/.env"; then
  echo "FAIL: disabling API encryption retained obsolete symmetric keys" >&2
  exit 1
fi

assert_invalid_api_encryption() {
  enabled="$1"
  request_key="$2"
  response_key="$3"
  invalid_path="$(mktemp -d "${temp_root}/invalid-api-encrypt.XXXXXX")"
  cp "${compose_file}" "${invalid_path}/docker-compose.prod.yml"
  if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
      DEPLOY_PATH="${invalid_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-api-encrypt" \
      MYSQL_ROOT_PASSWORD="test-root-password" \
      YUDAO_API_ENCRYPT_ENABLED="${enabled}" \
      YUDAO_API_ENCRYPT_REQUEST_KEY="${request_key}" \
      YUDAO_API_ENCRYPT_RESPONSE_KEY="${response_key}" \
      "${activation_script}" >/dev/null 2>&1; then
    echo "FAIL: activation accepted an invalid framework API encryption configuration" >&2
    exit 1
  fi
}

assert_invalid_api_encryption 'yes' '' ''
assert_invalid_api_encryption 'true' '' ''
assert_invalid_api_encryption 'true' 'short' 'response-key-0000000000000000001'
assert_invalid_api_encryption 'true' 'request-key-00000000000000000001' 'request-key-00000000000000000001'

# Legacy ciphertext cleanup remains available as an explicit, one-shot operator action.
cleanup_log="${temp_root}/cleanup.log"
STUB_LOG="${cleanup_log}" PATH="${stub_bin}:${PATH}" \
  DEPLOY_PATH="${deploy_path}" IMAGE_NAME="example/backend" IMAGE_TAG="cleanup" \
  MYSQL_ROOT_PASSWORD="test-root-password" MYSQL_DATABASE="skit_saas" \
  SKIT_CLEAR_LEGACY_AD_CREDENTIALS=1 \
  "${activation_script}" >/dev/null
if [[ "$(grep -c 'UPDATE\\ skit_ad_account\\ SET\\ app_key' "${cleanup_log}" || true)" -ne 1 ]]; then
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

invalid_callback_path="$(mktemp -d "${temp_root}/invalid-callback.XXXXXX")"
cp "${compose_file}" "${invalid_callback_path}/docker-compose.prod.yml"
if STUB_LOG="${stub_log}" PATH="${stub_bin}:${PATH}" \
    DEPLOY_PATH="${invalid_callback_path}" IMAGE_NAME="example/backend" IMAGE_TAG="invalid-callback" \
    MYSQL_ROOT_PASSWORD="test-root-password" \
    SKIT_AD_CALLBACK_PUBLIC_BASE_URL='https://user@example.com/app-api?tenant=42' \
    "${activation_script}" >/dev/null 2>&1; then
  echo "FAIL: activation accepted an ambiguous callback public base URL" >&2
  exit 1
fi

echo "PASS: activation safely persists and reuses independent advertising keys"
