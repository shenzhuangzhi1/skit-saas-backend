#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"
activation_script="${repo_root}/deploy/activate-backend.sh"
base_config="${repo_root}/yudao-server/src/main/resources/application.yaml"
local_config="${repo_root}/yudao-server/src/main/resources/application-local.yaml"
dev_config="${repo_root}/yudao-server/src/main/resources/application-dev.yaml"

if MYSQL_ROOT_PASSWORD=test SKIT_AD_ENCRYPTION_KEY= \
    SKIT_AD_CREDENTIAL_KEY=test-only-credential-key-0000001 \
    SKIT_AD_SESSION_TOKEN_KEY=test-only-session-token-key-00001 \
    SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api \
    docker compose -f "${compose_file}" config >/dev/null 2>&1; then
  echo "FAIL: production Compose accepted an empty advertising encryption key" >&2
  exit 1
fi

if MYSQL_ROOT_PASSWORD=test SKIT_AD_ENCRYPTION_KEY=test-only-key-000000000000000001 \
    SKIT_AD_CREDENTIAL_KEY= SKIT_AD_SESSION_TOKEN_KEY=test-only-session-token-key-00001 \
    SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api \
    docker compose -f "${compose_file}" config >/dev/null 2>&1; then
  echo "FAIL: production Compose accepted an empty dedicated credential key" >&2
  exit 1
fi

if MYSQL_ROOT_PASSWORD=test SKIT_AD_ENCRYPTION_KEY=test-only-key-000000000000000001 \
    SKIT_AD_CREDENTIAL_KEY=test-only-credential-key-0000001 SKIT_AD_SESSION_TOKEN_KEY= \
    SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api \
    docker compose -f "${compose_file}" config >/dev/null 2>&1; then
  echo "FAIL: production Compose accepted an empty advertising session-token key" >&2
  exit 1
fi

MYSQL_ROOT_PASSWORD=test SKIT_AD_ENCRYPTION_KEY=test-only-key-000000000000000001 \
  SKIT_AD_CREDENTIAL_KEY=test-only-credential-key-0000001 SKIT_AD_CREDENTIAL_KEY_ID=primary \
  SKIT_AD_SESSION_TOKEN_KEY=test-only-session-token-key-00001 SKIT_AD_SESSION_TOKEN_KEY_VERSION=1 \
  SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api \
  docker compose -f "${compose_file}" config >/dev/null

if ! grep -q 'upsert_env SKIT_AD_ENCRYPTION_KEY' "${activation_script}"; then
  echo "FAIL: activation does not persist the generated or injected encryption key" >&2
  exit 1
fi
if ! grep -q 'upsert_env SKIT_AD_CREDENTIAL_KEY' "${activation_script}" ||
   ! grep -q 'upsert_env SKIT_AD_CREDENTIAL_KEY_ID' "${activation_script}"; then
  echo "FAIL: activation does not persist the dedicated credential key and stable key id" >&2
  exit 1
fi
if ! grep -q 'upsert_env SKIT_AD_SESSION_TOKEN_KEY' "${activation_script}" ||
   ! grep -q 'upsert_env SKIT_AD_SESSION_TOKEN_KEY_VERSION' "${activation_script}"; then
  echo "FAIL: activation does not persist the session-token key and key version" >&2
  exit 1
fi
if ! grep -q 'openssl rand -hex 16' "${activation_script}"; then
  echo "FAIL: generated AES key must contain 32 single-byte characters" >&2
  exit 1
fi
if ! grep -q 'openssl rand -hex 32' "${activation_script}"; then
  echo "FAIL: generated session-token key must contain at least 32 safe ASCII characters" >&2
  exit 1
fi

encryptor_value="$(sed -n '/^  encryptor:/,/^[^ ]/p' "${base_config}" | sed -n 's/^[[:space:]]*password:[[:space:]]*//p')"
if [[ "${encryptor_value}" != '${SKIT_AD_ENCRYPTION_KEY:}' ]]; then
  echo "FAIL: base configuration must not contain a usable advertising encryption key" >&2
  exit 1
fi

for profile_config in "${local_config}" "${dev_config}"; do
  profile_value="$(sed -n '/^mybatis-plus:/,/^---/p' "${profile_config}" \
    | sed -n 's/^[[:space:]]*password:[[:space:]]*//p' | head -n 1)"
  if [[ "${profile_value}" != '${SKIT_AD_ENCRYPTION_KEY:}' ]]; then
    echo "FAIL: ${profile_config} must not commit a usable advertising encryption key" >&2
    exit 1
  fi
done

echo "PASS: advertising encryption keys are external, independent, and persistent"
