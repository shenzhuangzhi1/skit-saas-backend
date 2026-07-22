#!/usr/bin/env bash
set -euo pipefail

: "${DEPLOY_PATH:?DEPLOY_PATH is required}"
: "${IMAGE_NAME:?IMAGE_NAME is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"

cd "${DEPLOY_PATH}"

docker_config=""
health_body_file=""
backend_log_file=""
portable_lock_dir=""
server_env_file="${SERVER_ENV_FILE:-}"
cleanup_server_env=0

cleanup() {
  exit_code=$?
  trap - EXIT
  if [ -n "${health_body_file}" ]; then
    rm -f "${health_body_file}"
  fi
  if [ -n "${backend_log_file}" ]; then
    rm -f "${backend_log_file}"
  fi
  if [ -n "${docker_config}" ]; then
    rm -rf "${docker_config}"
  fi
  if [ "${cleanup_server_env}" = "1" ] && [ -n "${server_env_file}" ]; then
    rm -f "${server_env_file}"
  fi
  if [ -n "${portable_lock_dir}" ]; then
    rmdir "${portable_lock_dir}" >/dev/null 2>&1 || true
  fi
  exit "${exit_code}"
}
trap cleanup EXIT

deploy_lock_wait_seconds="${SKIT_DEPLOY_LOCK_WAIT_SECONDS:-900}"
if [[ ! "${deploy_lock_wait_seconds}" =~ ^[0-9]+$ ]]; then
  echo "SKIT_DEPLOY_LOCK_WAIT_SECONDS must be a non-negative integer."
  exit 1
fi
if command -v flock >/dev/null 2>&1; then
  exec 9> .deploy.lock
  if ! flock -w "${deploy_lock_wait_seconds}" 9; then
    echo "Another backend or frontend activation holds ${DEPLOY_PATH}/.deploy.lock."
    exit 1
  fi
else
  portable_lock_dir=".deploy.lock.d"
  lock_deadline=$((SECONDS + deploy_lock_wait_seconds))
  until mkdir "${portable_lock_dir}" 2>/dev/null; do
    if [ "${SECONDS}" -ge "${lock_deadline}" ]; then
      portable_lock_dir=""
      echo "Another backend or frontend activation holds ${DEPLOY_PATH}/.deploy.lock."
      exit 1
    fi
    sleep 1
  done
fi

release_bundle_path="${RELEASE_BUNDLE_PATH:-}"
if [ -n "${release_bundle_path}" ]; then
  case "${release_bundle_path}" in
    releases/backend-[A-Za-z0-9._-]*) ;;
    *)
      echo "RELEASE_BUNDLE_PATH must identify a staged backend release."
      exit 1
      ;;
  esac
  if [ -z "${server_env_file}" ]; then
    server_env_file="${release_bundle_path}/server.env"
  fi
  cleanup_server_env=1
  if [ -L "${release_bundle_path}" ] || [ ! -d "${release_bundle_path}" ]; then
    echo "The staged backend release directory is missing or unsafe."
    exit 1
  fi
  for release_file in docker-compose.prod.yml ruoyi-vue-pro.sql skit-saas.sql quartz.sql; do
    if [ -L "${release_bundle_path}/${release_file}" ] ||
       [ ! -f "${release_bundle_path}/${release_file}" ]; then
      echo "The staged backend release is missing ${release_file}."
      exit 1
    fi
  done
  staged_compose="$(mktemp)"
  cp "${release_bundle_path}/docker-compose.prod.yml" "${staged_compose}"
  chmod 644 "${staged_compose}"
  mv "${staged_compose}" docker-compose.prod.yml
  mkdir -p mysql/init
  install -m 644 "${release_bundle_path}/ruoyi-vue-pro.sql" mysql/init/ruoyi-vue-pro.sql
  install -m 644 "${release_bundle_path}/skit-saas.sql" mysql/init/skit-saas.sql
  install -m 644 "${release_bundle_path}/quartz.sql" mysql/init/quartz.sql
elif [ -z "${server_env_file}" ]; then
  server_env_file="server.env"
fi

persisted_credential_key=""
persisted_credential_key_id=""
persisted_session_token_key=""
persisted_session_token_key_version=""
set -a
if [ -f .env ]; then
  # shellcheck disable=SC1091
  . ./.env
  persisted_credential_key="${SKIT_AD_CREDENTIAL_KEY:-}"
  persisted_credential_key_id="${SKIT_AD_CREDENTIAL_KEY_ID:-}"
  persisted_session_token_key="${SKIT_AD_SESSION_TOKEN_KEY:-}"
  persisted_session_token_key_version="${SKIT_AD_SESSION_TOKEN_KEY_VERSION:-}"
fi
if [ -e "${server_env_file}" ] || [ -L "${server_env_file}" ]; then
  if [ -L "${server_env_file}" ] || [ ! -f "${server_env_file}" ]; then
    echo "The uploaded server environment must be a regular file."
    exit 1
  fi
  chmod 600 "${server_env_file}"
  cleanup_server_env=1
  # shellcheck disable=SC1091
  . "${server_env_file}"
  rm -f -- "${server_env_file}"
  cleanup_server_env=0
  server_env_file=""
fi
set +a
export -n GHCR_TOKEN SUDO_PASSWORD 2>/dev/null || true

# The persisted server-side .env is the source of truth after first activation. A newly uploaded
# server.env may seed a missing key, but it must not silently rotate an existing key/key-id pair.
if [ -n "${persisted_credential_key}" ]; then
  SKIT_AD_CREDENTIAL_KEY="${persisted_credential_key}"
  SKIT_AD_CREDENTIAL_KEY_ID="${persisted_credential_key_id:-primary}"
fi
if [ -n "${persisted_session_token_key}" ]; then
  SKIT_AD_SESSION_TOKEN_KEY="${persisted_session_token_key}"
  SKIT_AD_SESSION_TOKEN_KEY_VERSION="${persisted_session_token_key_version:-1}"
fi
upsert_env() {
  key="$1"
  value="$2"
  temp_file="$(mktemp)"
  if [ -f .env ]; then
    grep -v "^${key}=" .env > "${temp_file}" || true
  fi
  printf '%s=%s\n' "${key}" "${value}" >> "${temp_file}"
  mv "${temp_file}" .env
  chmod 600 .env
}

remove_env() {
  key="$1"
  if [ ! -f .env ]; then
    return
  fi
  temp_file="$(mktemp)"
  grep -v "^${key}=" .env > "${temp_file}" || true
  mv "${temp_file}" .env
  chmod 600 .env
}

DOCKER_USE_SUDO=0
DOCKER_SUDO_PASSWORD=0

sudo_cmd() {
  if [ "${DOCKER_SUDO_PASSWORD}" = "1" ]; then
    printf '%s\n' "${SUDO_PASSWORD}" | sudo -S -p '' "$@"
  else
    sudo -n "$@"
  fi
}

docker_cmd() {
  if [ "${DOCKER_USE_SUDO}" = "1" ]; then
    if [ -n "${DOCKER_CONFIG:-}" ]; then
      sudo_cmd env DOCKER_CONFIG="${DOCKER_CONFIG}" docker "$@"
    else
      sudo_cmd docker "$@"
    fi
  else
    docker "$@"
  fi
}

compose_cmd() {
  if [ "${DOCKER_USE_SUDO}" = "1" ]; then
    if [ -n "${DOCKER_CONFIG:-}" ]; then
      sudo_cmd env DOCKER_CONFIG="${DOCKER_CONFIG}" docker-compose "$@"
    else
      sudo_cmd docker-compose "$@"
    fi
  else
    docker-compose "$@"
  fi
}

prepare_docker_access() {
  if docker version >/dev/null 2>&1; then
    return
  fi
  if command -v sudo >/dev/null 2>&1 && sudo -n docker version >/dev/null 2>&1; then
    DOCKER_USE_SUDO=1
    return
  fi
  if [ -n "${SUDO_PASSWORD:-}" ] && command -v sudo >/dev/null 2>&1 \
    && printf '%s\n' "${SUDO_PASSWORD}" | sudo -S -p '' docker version >/dev/null 2>&1; then
    DOCKER_USE_SUDO=1
    DOCKER_SUDO_PASSWORD=1
    return
  fi
  echo "Docker is not accessible for this SSH user. Add the user to the docker group or configure sudo access."
  exit 1
}

compose() {
  if docker_cmd compose version >/dev/null 2>&1; then
    docker_cmd compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    compose_cmd "$@"
  else
    echo "Docker Compose is not installed."
    exit 1
  fi
}

prepare_docker_access

if [ -n "${GHCR_TOKEN:-}" ]; then
  docker_config="$(mktemp -d)"
  export DOCKER_CONFIG="${docker_config}"
  printf '%s' "${GHCR_TOKEN}" | docker_cmd login ghcr.io -u "${GHCR_USERNAME:-github-actions}" --password-stdin
fi

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
  else
    MYSQL_ROOT_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
  fi
fi

# The upstream API-encryption feature is optional and currently has no annotated production
# endpoint. Keep disabled releases keyless, but fail before replacing the container when an
# operator explicitly enables the AES mode without a safe coordinated key pair.
YUDAO_API_ENCRYPT_ENABLED="${YUDAO_API_ENCRYPT_ENABLED:-false}"
case "${YUDAO_API_ENCRYPT_ENABLED}" in
  true|false) ;;
  *)
    echo "YUDAO_API_ENCRYPT_ENABLED must be true or false."
    exit 1
    ;;
esac
YUDAO_API_ENCRYPT_REQUEST_KEY="${YUDAO_API_ENCRYPT_REQUEST_KEY:-}"
YUDAO_API_ENCRYPT_RESPONSE_KEY="${YUDAO_API_ENCRYPT_RESPONSE_KEY:-}"
if [ "${YUDAO_API_ENCRYPT_ENABLED}" = "true" ]; then
  for api_encrypt_key_name in YUDAO_API_ENCRYPT_REQUEST_KEY YUDAO_API_ENCRYPT_RESPONSE_KEY; do
    api_encrypt_key="${!api_encrypt_key_name}"
    case "${#api_encrypt_key}" in
      32) ;;
      *)
        echo "${api_encrypt_key_name} must contain exactly 32 single-byte characters when API encryption is enabled."
        exit 1
        ;;
    esac
    if [[ ! "${api_encrypt_key}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
      echo "${api_encrypt_key_name} contains unsafe characters for server-side environment persistence."
      exit 1
    fi
  done
  if [ "${YUDAO_API_ENCRYPT_REQUEST_KEY}" = "${YUDAO_API_ENCRYPT_RESPONSE_KEY}" ]; then
    echo "API request and response encryption keys must be independent."
    exit 1
  fi
fi

# Generate the legacy advertising field-encryption key once, then reuse the persisted value on
# every release. An operator may inject the key before the first deployment to use a managed
# Secret.
if [ -z "${SKIT_AD_ENCRYPTION_KEY:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    SKIT_AD_ENCRYPTION_KEY="$(openssl rand -hex 16)"
  else
    SKIT_AD_ENCRYPTION_KEY="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
  fi
fi
case "${#SKIT_AD_ENCRYPTION_KEY}" in
  16|24|32) ;;
  *)
    echo "SKIT_AD_ENCRYPTION_KEY must contain exactly 16, 24, or 32 single-byte characters."
    exit 1
    ;;
esac
if [[ ! "${SKIT_AD_ENCRYPTION_KEY}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
  echo "SKIT_AD_ENCRYPTION_KEY contains unsafe characters for server-side environment persistence."
  exit 1
fi

# Credential envelopes use a dedicated key so their lifecycle and rotation are independent from
# the legacy encrypted account fields. Generate it only when no persisted or injected value exists.
if [ -z "${SKIT_AD_CREDENTIAL_KEY:-}" ]; then
  while :; do
    if command -v openssl >/dev/null 2>&1; then
      SKIT_AD_CREDENTIAL_KEY="$(openssl rand -hex 16)"
    else
      SKIT_AD_CREDENTIAL_KEY="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
    fi
    if [ "${SKIT_AD_CREDENTIAL_KEY}" != "${SKIT_AD_ENCRYPTION_KEY}" ]; then
      break
    fi
  done
fi
case "${#SKIT_AD_CREDENTIAL_KEY}" in
  16|24|32) ;;
  *)
    echo "SKIT_AD_CREDENTIAL_KEY must contain exactly 16, 24, or 32 single-byte characters."
    exit 1
    ;;
esac
if [[ ! "${SKIT_AD_CREDENTIAL_KEY}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
  echo "SKIT_AD_CREDENTIAL_KEY contains unsafe characters for server-side environment persistence."
  exit 1
fi
if [ "${SKIT_AD_CREDENTIAL_KEY}" = "${SKIT_AD_ENCRYPTION_KEY}" ]; then
  echo "SKIT_AD_CREDENTIAL_KEY must not reuse SKIT_AD_ENCRYPTION_KEY."
  exit 1
fi

# Session customData is authenticated with its own key. Keeping this key independent means an
# advertising credential rotation cannot invalidate active reward sessions, and a signing-key
# rotation cannot decrypt provider secrets. Generate it once and keep the server-side .env value.
if [ -z "${SKIT_AD_SESSION_TOKEN_KEY:-}" ]; then
  while :; do
    if command -v openssl >/dev/null 2>&1; then
      SKIT_AD_SESSION_TOKEN_KEY="$(openssl rand -hex 32)"
    else
      SKIT_AD_SESSION_TOKEN_KEY="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    fi
    if [ "${SKIT_AD_SESSION_TOKEN_KEY}" != "${SKIT_AD_ENCRYPTION_KEY}" ] &&
       [ "${SKIT_AD_SESSION_TOKEN_KEY}" != "${SKIT_AD_CREDENTIAL_KEY}" ]; then
      break
    fi
  done
fi
if [ "${#SKIT_AD_SESSION_TOKEN_KEY}" -lt 32 ]; then
  echo "SKIT_AD_SESSION_TOKEN_KEY must contain at least 32 single-byte characters."
  exit 1
fi
if [[ ! "${SKIT_AD_SESSION_TOKEN_KEY}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
  echo "SKIT_AD_SESSION_TOKEN_KEY contains unsafe characters for server-side environment persistence."
  exit 1
fi
if [ "${SKIT_AD_SESSION_TOKEN_KEY}" = "${SKIT_AD_ENCRYPTION_KEY}" ] ||
   [ "${SKIT_AD_SESSION_TOKEN_KEY}" = "${SKIT_AD_CREDENTIAL_KEY}" ]; then
  echo "SKIT_AD_SESSION_TOKEN_KEY must not reuse an encryption or credential key."
  exit 1
fi

SKIT_AD_SESSION_TOKEN_KEY_VERSION="${SKIT_AD_SESSION_TOKEN_KEY_VERSION:-1}"
if [[ ! "${SKIT_AD_SESSION_TOKEN_KEY_VERSION}" =~ ^[1-9][0-9]{0,9}$ ]] ||
   { [ "${#SKIT_AD_SESSION_TOKEN_KEY_VERSION}" -eq 10 ] &&
     [[ "${SKIT_AD_SESSION_TOKEN_KEY_VERSION}" > "2147483647" ]]; }; then
  echo "SKIT_AD_SESSION_TOKEN_KEY_VERSION must be a positive 32-bit integer."
  exit 1
fi

SKIT_AD_CREDENTIAL_KEY_ID="${SKIT_AD_CREDENTIAL_KEY_ID:-primary}"
if [[ ! "${SKIT_AD_CREDENTIAL_KEY_ID}" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
  echo "SKIT_AD_CREDENTIAL_KEY_ID must contain 1 to 64 safe identifier characters."
  exit 1
fi

# Callback templates use one explicit deployment origin. Never infer it from an incoming Host or
# X-Forwarded-* header. A localhost HTTP default keeps first-time offline activation possible, but
# readiness deliberately blocks ENFORCED until the operator supplies an HTTPS public origin.
SKIT_AD_CALLBACK_PUBLIC_BASE_URL="${SKIT_AD_CALLBACK_PUBLIC_BASE_URL:-http://localhost:${BACKEND_PORT:-48080}/app-api}"
SKIT_AD_CALLBACK_PUBLIC_BASE_URL="${SKIT_AD_CALLBACK_PUBLIC_BASE_URL%/}"
if [[ ! "${SKIT_AD_CALLBACK_PUBLIC_BASE_URL}" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?/app-api$ ]]; then
  echo "SKIT_AD_CALLBACK_PUBLIC_BASE_URL must be an absolute http(s) URL ending in /app-api without userinfo, query, or fragment."
  exit 1
fi
callback_authority="${SKIT_AD_CALLBACK_PUBLIC_BASE_URL#*://}"
callback_authority="${callback_authority%/app-api}"
if [[ "${callback_authority}" == *:* ]]; then
  callback_port="${callback_authority##*:}"
  if [ "${callback_port}" -gt 65535 ]; then
    echo "SKIT_AD_CALLBACK_PUBLIC_BASE_URL contains an invalid port."
    exit 1
  fi
fi

case "${SKIT_CLEAR_LEGACY_AD_CREDENTIALS:-0}" in
  0|1) ;;
  *)
    echo "SKIT_CLEAR_LEGACY_AD_CREDENTIALS must be 0 or 1."
    exit 1
    ;;
esac

validate_retained_keyring() {
  file="$1"
  seen_file="$(mktemp)"
  valid=1
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ''|'#'*) continue ;;
    esac
    if [[ "${line}" != *=* ]] || [[ "${line}" =~ ^[[:space:]] ]] ||
       [[ "${line}" =~ [[:space:]]$ ]]; then
      valid=0
      break
    fi
    property_name="${line%%=*}"
    property_value="${line#*=}"
    if grep -Fqx -- "${property_name}" "${seen_file}"; then
      valid=0
      break
    fi
    printf '%s\n' "${property_name}" >> "${seen_file}"
    case "${property_name}" in
      skit.ad.credential-encryption.keys.*)
        retained_id="${property_name#skit.ad.credential-encryption.keys.}"
        if [[ ! "${retained_id}" =~ ^[A-Za-z0-9._-]{1,64}$ ]] ||
           [[ ! "${property_value}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
          valid=0
          break
        fi
        case "${#property_value}" in
          16|24|32) ;;
          *) valid=0; break ;;
        esac
        if [ "${retained_id}" = "${SKIT_AD_CREDENTIAL_KEY_ID}" ] &&
           [ "${property_value}" != "${SKIT_AD_CREDENTIAL_KEY}" ]; then
          valid=0
          break
        fi
        ;;
      skit.ad.session-token.keys.*)
        retained_version="${property_name#skit.ad.session-token.keys.}"
        if [[ ! "${retained_version}" =~ ^[1-9][0-9]{0,9}$ ]] ||
           { [ "${#retained_version}" -eq 10 ] && [[ "${retained_version}" > "2147483647" ]]; } ||
           [ "${#property_value}" -lt 32 ] ||
           [[ ! "${property_value}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
          valid=0
          break
        fi
        if [ "${retained_version}" = "${SKIT_AD_SESSION_TOKEN_KEY_VERSION}" ] &&
           [ "${property_value}" != "${SKIT_AD_SESSION_TOKEN_KEY}" ]; then
          valid=0
          break
        fi
        ;;
      *)
        valid=0
        break
        ;;
    esac
  done < "${file}"
  rm -f "${seen_file}"
  if [ "${valid}" != "1" ]; then
    echo "Retained advertising keyring is invalid; only validated retained key properties are allowed."
    return 1
  fi
}

decode_retained_keyring() {
  encoded="$1"
  destination="$2"
  if printf '%s' "${encoded}" | base64 --decode > "${destination}" 2>/dev/null; then
    return
  fi
  if printf '%s' "${encoded}" | base64 -D > "${destination}" 2>/dev/null; then
    return
  fi
  echo "SKIT_AD_RETAINED_KEYRING_BASE64 is not valid base64."
  return 1
}

prepare_retained_keyring() {
  keyring_file="ad-keyring.properties"
  if [ -L "${keyring_file}" ] || { [ -e "${keyring_file}" ] && [ ! -f "${keyring_file}" ]; }; then
    echo "Retained advertising keyring must be a regular file."
    return 1
  fi

  decoded_file=""
  if [ -n "${SKIT_AD_RETAINED_KEYRING_BASE64:-}" ]; then
    decoded_file="$(mktemp)"
    if ! decode_retained_keyring "${SKIT_AD_RETAINED_KEYRING_BASE64}" "${decoded_file}" ||
       ! validate_retained_keyring "${decoded_file}"; then
      rm -f "${decoded_file}"
      return 1
    fi
  fi

  if [ -f "${keyring_file}" ]; then
    if ! validate_retained_keyring "${keyring_file}"; then
      rm -f "${decoded_file}"
      return 1
    fi
    if [ -n "${decoded_file}" ] && ! cmp -s "${decoded_file}" "${keyring_file}"; then
      rm -f "${decoded_file}"
      echo "Retained advertising keyring already exists and cannot be replaced by a routine release."
      return 1
    fi
  else
    staged_file="$(mktemp)"
    if [ -n "${decoded_file}" ]; then
      cp "${decoded_file}" "${staged_file}"
    else
      : > "${staged_file}"
    fi
    chmod 600 "${staged_file}"
    mv "${staged_file}" "${keyring_file}"
  fi
  rm -f "${decoded_file}"
  chmod 600 "${keyring_file}"
}

prepare_retained_keyring

upsert_env MYSQL_ROOT_PASSWORD "${MYSQL_ROOT_PASSWORD}"
upsert_env YUDAO_API_ENCRYPT_ENABLED "${YUDAO_API_ENCRYPT_ENABLED}"
if [ "${YUDAO_API_ENCRYPT_ENABLED}" = "true" ]; then
  upsert_env YUDAO_API_ENCRYPT_REQUEST_KEY "${YUDAO_API_ENCRYPT_REQUEST_KEY}"
  upsert_env YUDAO_API_ENCRYPT_RESPONSE_KEY "${YUDAO_API_ENCRYPT_RESPONSE_KEY}"
else
  remove_env YUDAO_API_ENCRYPT_REQUEST_KEY
  remove_env YUDAO_API_ENCRYPT_RESPONSE_KEY
  unset YUDAO_API_ENCRYPT_REQUEST_KEY YUDAO_API_ENCRYPT_RESPONSE_KEY
fi
upsert_env SKIT_AD_ENCRYPTION_KEY "${SKIT_AD_ENCRYPTION_KEY}"
upsert_env SKIT_AD_CREDENTIAL_KEY "${SKIT_AD_CREDENTIAL_KEY}"
upsert_env SKIT_AD_CREDENTIAL_KEY_ID "${SKIT_AD_CREDENTIAL_KEY_ID}"
upsert_env SKIT_AD_SESSION_TOKEN_KEY "${SKIT_AD_SESSION_TOKEN_KEY}"
upsert_env SKIT_AD_SESSION_TOKEN_KEY_VERSION "${SKIT_AD_SESSION_TOKEN_KEY_VERSION}"
upsert_env SKIT_AD_CALLBACK_PUBLIC_BASE_URL "${SKIT_AD_CALLBACK_PUBLIC_BASE_URL}"
upsert_env MYSQL_DATABASE "${MYSQL_DATABASE:-skit_saas}"
upsert_env MYSQL_PORT "${MYSQL_PORT:-3306}"
upsert_env REDIS_PORT "${REDIS_PORT:-6379}"
upsert_env BACKEND_PORT "${BACKEND_PORT:-48080}"
upsert_env BACKEND_HEALTH_PATH "${BACKEND_HEALTH_PATH:-/actuator/health}"
upsert_env FRONTEND_PORT "${FRONTEND_PORT:-48081}"
upsert_env BACKEND_IMAGE "${IMAGE_NAME}"
upsert_env BACKEND_IMAGE_TAG "${IMAGE_TAG}"

compose -f docker-compose.prod.yml --env-file .env pull backend
compose -f docker-compose.prod.yml --env-file .env up -d mysql redis

mysql_ready=0
for _ in $(seq 1 60); do
  if compose -f docker-compose.prod.yml --env-file .env exec -T \
      mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqladmin -uroot --silent ping' \
      >/dev/null 2>&1; then
    mysql_ready=1
    break
  fi
  sleep 2
done
if [ "${mysql_ready}" != "1" ]; then
  echo "MySQL did not become ready for backend activation."
  exit 1
fi

mysql_in_container() {
  compose -f docker-compose.prod.yml --env-file .env exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE" "$@"' \
    mysql-in-container "$@"
}

# Keep the release self-diagnosing without exposing row data or encrypted credentials.  The
# application can be HTTP-healthy while an old production database still carries a stale table
# shape, so print the structural facts needed to diagnose write failures in the activation log.
print_skit_schema_summary() {
  local summary_sql="SELECT 'MIGRATIONS' AS kind,\`version\`,\`description\` FROM \`skit_schema_migration\` ORDER BY \`version\`; \
SELECT 'COLUMNS' AS kind,\`TABLE_NAME\`,\`COLUMN_NAME\`,\`COLUMN_TYPE\`,\`IS_NULLABLE\`,\`COLUMN_DEFAULT\`,\`EXTRA\` \
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() \
  AND TABLE_NAME IN ('system_tenant','skit_agent','skit_ad_account','skit_ad_network_capability','skit_management_command_audit') \
  ORDER BY \`TABLE_NAME\`,\`ORDINAL_POSITION\`; \
SELECT 'INDEXES' AS kind,\`TABLE_NAME\`,\`INDEX_NAME\`,\`NON_UNIQUE\`,\`SEQ_IN_INDEX\`,\`COLUMN_NAME\` \
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() \
  AND TABLE_NAME IN ('skit_ad_network_capability','skit_management_command_audit') \
  ORDER BY \`TABLE_NAME\`,\`INDEX_NAME\`,\`SEQ_IN_INDEX\`; \
SELECT 'CHECKS' AS kind,\`tc\`.\`TABLE_NAME\`,\`tc\`.\`CONSTRAINT_NAME\`,\`cc\`.\`CHECK_CLAUSE\` \
  FROM information_schema.TABLE_CONSTRAINTS \`tc\` \
  JOIN information_schema.CHECK_CONSTRAINTS \`cc\` \
    ON \`cc\`.\`CONSTRAINT_SCHEMA\`=\`tc\`.\`CONSTRAINT_SCHEMA\` \
   AND \`cc\`.\`CONSTRAINT_NAME\`=\`tc\`.\`CONSTRAINT_NAME\` \
  WHERE \`tc\`.\`TABLE_SCHEMA\`=DATABASE() \
  AND \`tc\`.\`TABLE_NAME\` IN ('skit_ad_network_capability','skit_management_command_audit') \
  AND \`tc\`.\`CONSTRAINT_TYPE\`='CHECK' \
  ORDER BY \`tc\`.\`TABLE_NAME\`,\`tc\`.\`CONSTRAINT_NAME\`; \
SELECT 'TRIGGERS' AS kind,\`TRIGGER_NAME\`,\`EVENT_OBJECT_TABLE\`,\`ACTION_TIMING\`,\`EVENT_MANIPULATION\` \
  FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() \
  AND EVENT_OBJECT_TABLE IN ('system_tenant','skit_agent','skit_ad_account','skit_ad_network_capability','skit_management_command_audit') \
  ORDER BY \`EVENT_OBJECT_TABLE\`,\`TRIGGER_NAME\`;"
  echo "Skit production schema summary (structure only):"
  mysql_in_container --batch --skip-column-names -e "${summary_sql}"
}

# Keep this diagnostic deliberately narrower than the error-log record: never print request data,
# exception messages, stack traces, IP addresses, or user-agent data into the deployment log.  It
# is also best-effort: diagnostic availability must never decide whether a healthy release stays
# active.
print_network_capability_error_diagnostic() {
  local diagnostic_sql="SELECT /*+ MAX_EXECUTION_TIME(2000) */ \`exception_name\`,
    CASE WHEN \`exception_root_cause_message\`
      REGEXP '^[A-Za-z_$][A-Za-z0-9_$]*([.][A-Za-z_$][A-Za-z0-9_$]*)+$'
      AND \`exception_root_cause_message\` NOT REGEXP '[[:cntrl:]]'
      THEN \`exception_root_cause_message\` ELSE '<redacted>' END AS \`root_cause_type\`,
    \`exception_class_name\`,\`exception_method_name\`,\`exception_line_number\`,\`exception_time\`
  FROM (
    SELECT \`id\`,\`request_method\`,\`request_url\`,\`deleted\`,\`exception_name\`,
      \`exception_root_cause_message\`,\`exception_class_name\`,\`exception_method_name\`,
      \`exception_line_number\`,\`exception_time\`
    FROM \`infra_api_error_log\` ORDER BY \`id\` DESC LIMIT 500
  ) AS \`recent_errors\`
  WHERE \`request_method\`='PUT'
    AND \`request_url\`='/admin-api/skit/tenant/ad-readiness/network-capability'
    AND \`deleted\`=b'0'
  ORDER BY \`id\` DESC LIMIT 1;"
  local diagnostic_output=""
  if ! diagnostic_output="$(mysql_in_container --batch -e "${diagnostic_sql}" 2>/dev/null)"; then
    echo "Latest network-capability API error unavailable (metadata query failed safely)."
    return 0
  fi
  echo "Latest network-capability API error (safe type/location/time metadata only):"
  if [ -n "${diagnostic_output}" ]; then
    printf '%s\n' "${diagnostic_output}"
  else
    echo "<none>"
  fi
}

quartz_table_names="'QRTZ_BLOB_TRIGGERS','QRTZ_CALENDARS','QRTZ_CRON_TRIGGERS','QRTZ_FIRED_TRIGGERS','QRTZ_JOB_DETAILS','QRTZ_LOCKS','QRTZ_PAUSED_TRIGGER_GRPS','QRTZ_SCHEDULER_STATE','QRTZ_SIMPLE_TRIGGERS','QRTZ_SIMPROP_TRIGGERS','QRTZ_TRIGGERS'"
quartz_schema_state_sql="SELECT CONCAT((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB' AND TABLE_NAME IN (${quartz_table_names})), ':', (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (${quartz_table_names})), ':', (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'PRIMARY KEY' AND TABLE_NAME IN (${quartz_table_names})));"
quartz_schema_probe_sql="SELECT SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,BLOB_DATA FROM QRTZ_BLOB_TRIGGERS LIMIT 0; SELECT SCHED_NAME,CALENDAR_NAME,CALENDAR FROM QRTZ_CALENDARS LIMIT 0; SELECT SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,CRON_EXPRESSION,TIME_ZONE_ID FROM QRTZ_CRON_TRIGGERS LIMIT 0; SELECT SCHED_NAME,ENTRY_ID,TRIGGER_NAME,TRIGGER_GROUP,INSTANCE_NAME,FIRED_TIME,SCHED_TIME,PRIORITY,STATE,JOB_NAME,JOB_GROUP,IS_NONCONCURRENT,REQUESTS_RECOVERY FROM QRTZ_FIRED_TRIGGERS LIMIT 0; SELECT SCHED_NAME,JOB_NAME,JOB_GROUP,DESCRIPTION,JOB_CLASS_NAME,IS_DURABLE,IS_NONCONCURRENT,IS_UPDATE_DATA,REQUESTS_RECOVERY,JOB_DATA FROM QRTZ_JOB_DETAILS LIMIT 0; SELECT SCHED_NAME,LOCK_NAME FROM QRTZ_LOCKS LIMIT 0; SELECT SCHED_NAME,TRIGGER_GROUP FROM QRTZ_PAUSED_TRIGGER_GRPS LIMIT 0; SELECT SCHED_NAME,INSTANCE_NAME,LAST_CHECKIN_TIME,CHECKIN_INTERVAL FROM QRTZ_SCHEDULER_STATE LIMIT 0; SELECT SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,REPEAT_COUNT,REPEAT_INTERVAL,TIMES_TRIGGERED FROM QRTZ_SIMPLE_TRIGGERS LIMIT 0; SELECT SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,STR_PROP_1,STR_PROP_2,STR_PROP_3,INT_PROP_1,INT_PROP_2,LONG_PROP_1,LONG_PROP_2,DEC_PROP_1,DEC_PROP_2,BOOL_PROP_1,BOOL_PROP_2 FROM QRTZ_SIMPROP_TRIGGERS LIMIT 0; SELECT SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,JOB_NAME,JOB_GROUP,DESCRIPTION,NEXT_FIRE_TIME,PREV_FIRE_TIME,PRIORITY,TRIGGER_STATE,TRIGGER_TYPE,START_TIME,END_TIME,CALENDAR_NAME,MISFIRE_INSTR,JOB_DATA FROM QRTZ_TRIGGERS LIMIT 0;"
read_quartz_schema_state() {
  mysql_in_container --batch --skip-column-names -e "${quartz_schema_state_sql}" | tr -d '\r\n'
}
validate_quartz_schema_columns() {
  mysql_in_container --batch --skip-column-names -e "${quartz_schema_probe_sql}" >/dev/null
}

quartz_schema_state="$(read_quartz_schema_state)"
case "${quartz_schema_state}" in
  11:79:11)
    if ! validate_quartz_schema_columns; then
      echo "Quartz schema has incompatible columns; refusing backend activation."
      exit 1
    fi
    ;;
  0:0:0)
    if [ ! -f mysql/init/quartz.sql ]; then
      echo "Quartz schema is missing and mysql/init/quartz.sql is unavailable."
      exit 1
    fi
    mysql_in_container < mysql/init/quartz.sql
    quartz_schema_state_after="$(read_quartz_schema_state)"
    if [ "${quartz_schema_state_after}" != "11:79:11" ] ||
       ! validate_quartz_schema_columns; then
      echo "Quartz schema initialization did not produce the required structure."
      exit 1
    fi
    ;;
  *)
    echo "Quartz schema is incomplete or incompatible (${quartz_schema_state}); refusing an unsafe automatic rebuild."
    exit 1
    ;;
esac

if [ "${SKIT_CLEAR_LEGACY_AD_CREDENTIALS:-0}" = "1" ]; then
  # Destructive credential cleanup is intentionally opt-in. A missing local marker is not proof
  # that database ciphertext uses the legacy key (for example after disaster recovery).
  # Public account metadata and all revenue history remain intact.
  compose -f docker-compose.prod.yml --env-file .env stop backend >/dev/null 2>&1 || true
  credential_cleanup_sql="SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skit_ad_account'); SET @cleanup_sql := IF(@table_exists > 0, 'UPDATE skit_ad_account SET app_key = NULL, secret = NULL, status = 1, update_time = NOW(), updater = ''system-encryption-key-bootstrap''', 'SELECT 1'); PREPARE cleanup_statement FROM @cleanup_sql; EXECUTE cleanup_statement; DEALLOCATE PREPARE cleanup_statement;"
  mysql_in_container --batch --skip-column-names -e "${credential_cleanup_sql}" >/dev/null
fi

compose -f docker-compose.prod.yml --env-file .env \
  up -d --no-deps --force-recreate backend

health_url="http://127.0.0.1:${BACKEND_PORT:-48080}${BACKEND_HEALTH_PATH:-/actuator/health}"
health_body_file="$(mktemp)"
backend_log_file="$(mktemp)"
required_healthy_samples=5
healthy_samples=0
max_startup_restarts=3
last_restart_count=0
for _ in $(seq 1 90); do
  backend_state="$(docker_cmd inspect --format '{{.RestartCount}} {{.State.Status}}' \
    skit-saas-backend 2>/dev/null || true)"
  restart_count="${backend_state%% *}"
  container_state="${backend_state#* }"
  if [[ "${restart_count}" =~ ^[0-9]+$ ]]; then
    if ((restart_count > max_startup_restarts)); then
      echo "Backend restarted ${restart_count} time(s) during activation (maximum tolerated: ${max_startup_restarts})."
      break
    fi
    if ((restart_count != last_restart_count)); then
      healthy_samples=0
      last_restart_count="${restart_count}"
    fi
  fi
  : > "${health_body_file}"
  status_code="$(curl -sS --max-time 5 -o "${health_body_file}" -w '%{http_code}' "${health_url}" || true)"
  health_body="$(LC_ALL=C tr -d ' \t\r\n' < "${health_body_file}")"
  if [ "${container_state}" = "running" ] &&
     [ "${status_code}" = "200" ] && [ "${health_body}" = '{"status":"UP"}' ]; then
    healthy_samples=$((healthy_samples + 1))
    if [ "${healthy_samples}" -ge "${required_healthy_samples}" ]; then
      if ! docker_cmd logs skit-saas-backend > "${backend_log_file}" 2>&1; then
        cat "${backend_log_file}" >&2
        echo "Backend startup logs could not be read; refusing activation."
        break
      fi
      if grep -Eq 'JobPersistenceException|Table .*QRTZ_.*doesn.t exist' \
          "${backend_log_file}"; then
        cat "${backend_log_file}" >&2
        echo "Backend became HTTP-healthy while Quartz persistence was broken."
        break
      fi
      if ! print_skit_schema_summary; then
        echo "Skit schema summary failed; refusing activation because the production database cannot be inspected safely."
        break
      fi
      print_network_capability_error_diagnostic
      rm -f "${health_body_file}" "${backend_log_file}"
      health_body_file=""
      backend_log_file=""
      docker_cmd ps --filter name=skit-saas-backend
      exit 0
    fi
  else
    healthy_samples=0
  fi
  sleep 2
done

rm -f "${health_body_file}"
health_body_file=""
docker_cmd logs --tail 120 skit-saas-backend || true
exit 1
