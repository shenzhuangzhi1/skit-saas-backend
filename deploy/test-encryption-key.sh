#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"
activation_script="${repo_root}/deploy/activate-backend.sh"
base_config="${repo_root}/yudao-server/src/main/resources/application.yaml"
local_config="${repo_root}/yudao-server/src/main/resources/application-local.yaml"
dev_config="${repo_root}/yudao-server/src/main/resources/application-dev.yaml"

common_compose_environment=(
  MYSQL_ROOT_PASSWORD=test
  SKIT_AD_ENCRYPTION_KEY=test-only-key-000000000000000001
  SKIT_AD_CREDENTIAL_KEY=test-only-credential-key-0000001
  SKIT_AD_CREDENTIAL_KEY_ID=primary
  SKIT_AD_SESSION_TOKEN_KEY=test-only-session-token-key-00001
  SKIT_AD_SESSION_TOKEN_KEY_VERSION=1
  SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY_ID=provider_primary
  SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY=abcdef0123456789abcdef0123456789
  SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api
)

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
  SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY_ID=primary \
  SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY=abcdef0123456789abcdef0123456789 \
  SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api \
  docker compose -f "${compose_file}" config >/dev/null

if env "${common_compose_environment[@]}" SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY= \
    docker compose -f "${compose_file}" config >/dev/null 2>&1; then
  echo "FAIL: production Compose accepted an empty provider callback payload key" >&2
  exit 1
fi
if env "${common_compose_environment[@]}" SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY= \
    docker compose -f "${compose_file}" config >/dev/null 2>&1; then
  echo "FAIL: production Compose accepted an empty provider callback audit HMAC key" >&2
  exit 1
fi
env "${common_compose_environment[@]}" docker compose -f "${compose_file}" config >/dev/null

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
if ! grep -q 'upsert_env SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY' "${activation_script}" ||
   ! grep -q 'upsert_env SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY_ID' "${activation_script}"; then
  echo "FAIL: activation does not persist the provider callback payload key and stable key id" >&2
  exit 1
fi
if ! grep -q 'upsert_env SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY' "${activation_script}"; then
  echo "FAIL: activation does not persist the provider callback audit HMAC key" >&2
  exit 1
fi
for key_name in SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY; do
  grep -Fq "${key_name}" "${compose_file}" \
    || { echo "FAIL: production Compose does not pass ${key_name}" >&2; exit 1; }
done
grep -Fq 'skit.ad.provider-callback-payload-encryption.keys.' "${activation_script}" \
  || { echo "FAIL: activation does not retain provider payload keys for bounded decryptability" >&2; exit 1; }
grep -Fq 'prepare_provider_impression_gate_runtime_config' "${activation_script}" \
  || { echo "FAIL: activation does not stage signed gate evidence as an ephemeral runtime file" >&2; exit 1; }
for gate_environment in \
    SKIT_PROVIDER_IMPRESSION_GATE_ENVIRONMENT_FINGERPRINT \
    SKIT_PROVIDER_IMPRESSION_GATE_OPERATIONS_PUBLIC_KEY \
    SKIT_PROVIDER_IMPRESSION_GATE_MANIFEST_BASE64 \
    SKIT_PROVIDER_IMPRESSION_GATE_SIGNATURE; do
  if grep -Fq "upsert_env ${gate_environment}" "${activation_script}"; then
    echo "FAIL: activation persists ephemeral ${gate_environment} in .env" >&2
    exit 1
  fi
done
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
