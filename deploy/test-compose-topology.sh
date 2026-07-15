#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"
base_config="${repo_root}/yudao-server/src/main/resources/application.yaml"
runtime_config="${repo_root}/yudao-server/src/main/resources/application-runtime.yaml"
prod_config="${repo_root}/yudao-server/src/main/resources/application-prod.yaml"

yaml_value() {
  file="$1"
  wanted_path="$2"
  awk -v wanted_path="${wanted_path}" '
    BEGIN { wanted_count = split(wanted_path, wanted, ".") }
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      match($0, /^[ ]*/)
      indent = RLENGTH
      content = substr($0, indent + 1)
      colon = index(content, ":")
      if (colon == 0) { next }
      key = substr(content, 1, colon - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      level = int(indent / 2) + 1
      path[level] = key
      for (index_to_clear = level + 1; index_to_clear <= wanted_count; index_to_clear++) {
        delete path[index_to_clear]
      }
      if (level != wanted_count) { next }
      for (path_index = 1; path_index <= wanted_count; path_index++) {
        if (path[path_index] != wanted[path_index]) { next }
      }
      value = substr(content, colon + 1)
      sub(/[[:space:]]+#.*$/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      gsub(/^['\"']|['\"']$/, "", value)
      print value
      exit
    }
  ' "${file}"
}

assert_yaml_value() {
  file="$1"
  path="$2"
  expected="$3"
  actual="$(yaml_value "${file}" "${path}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "FAIL: ${path} must be ${expected} in ${file}; got ${actual:-<missing>}" >&2
    exit 1
  fi
}

frontend_service="$(sed -n '/^  frontend:/,/^volumes:/p' "${compose_file}")"
if grep -Eq '^    depends_on:' <<<"${frontend_service}"; then
  echo "FAIL: frontend must not depend on backend lifecycle" >&2
  exit 1
fi

backend_service="$(sed -n '/^  backend:/,/^  frontend:/p' "${compose_file}")"
if ! grep -Fq '127.0.0.1:${BACKEND_PORT:-48080}:48080' <<<"${backend_service}"; then
  echo "FAIL: backend host port must bind loopback so callbacks enter through the redacting proxy" >&2
  exit 1
fi
if ! grep -Eq '^[[:space:]]+SPRING_PROFILES_ACTIVE:[[:space:]]*"runtime,prod"[[:space:]]*$' \
    <<<"${backend_service}"; then
  echo "FAIL: production backend must load the secret-free runtime baseline and prod overlay" >&2
  exit 1
fi

redis_service="$(sed -n '/^  redis:/,/^  backend:/p' "${compose_file}")"
if ! grep -Eq '^[[:space:]]+image:[[:space:]]+redis:6\.2([.-]|$)' <<<"${redis_service}"; then
  echo "FAIL: Redis must be pinned to 6.2+ because one-time WebSocket tickets use atomic GETDEL" >&2
  exit 1
fi
for service_name in mysql redis; do
  service_block="$(sed -n "/^  ${service_name}:/,/^  [a-z]/p" "${compose_file}")"
  if ! grep -Eq '^[[:space:]]+- "127\.0\.0\.1:\$\{[A-Z_]+:-[0-9]+\}:[0-9]+"[[:space:]]*$' \
      <<<"${service_block}"; then
    echo "FAIL: ${service_name} host port must be absent or bound to loopback" >&2
    exit 1
  fi
done
for required_runtime_environment in \
    'MYSQL_HOST: mysql' \
    'MYSQL_SERVICE_PORT: 3306' \
    'MYSQL_PASSWORD: ${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}' \
    'REDIS_HOST: redis' \
    'REDIS_PORT: 6379' \
    'YUDAO_API_ENCRYPT_ENABLED: ${YUDAO_API_ENCRYPT_ENABLED:-false}' \
    'YUDAO_API_ENCRYPT_REQUEST_KEY: ${YUDAO_API_ENCRYPT_REQUEST_KEY:-}' \
    'YUDAO_API_ENCRYPT_RESPONSE_KEY: ${YUDAO_API_ENCRYPT_RESPONSE_KEY:-}' \
    'SKIT_TRUSTED_PROXY_CIDRS: ${SKIT_TRUSTED_PROXY_CIDRS:-172.16.0.0/12}'; do
  if ! grep -Fq -- "${required_runtime_environment}" <<<"${backend_service}"; then
    echo "FAIL: production runtime is missing ${required_runtime_environment}" >&2
    exit 1
  fi
done

if [[ ! -f "${runtime_config}" ]]; then
  echo "FAIL: production runtime baseline is missing: ${runtime_config}" >&2
  exit 1
fi
if [[ ! -f "${prod_config}" ]]; then
  echo "FAIL: production overlay is missing: ${prod_config}" >&2
  exit 1
fi
assert_yaml_value "${runtime_config}" "server.port" "48080"
assert_yaml_value "${runtime_config}" "spring.datasource.dynamic.primary" "master"
assert_yaml_value "${runtime_config}" "spring.redis.host" '${REDIS_HOST:redis}'
assert_yaml_value "${runtime_config}" "spring.redis.port" '${REDIS_PORT:6379}'
assert_yaml_value "${runtime_config}" "spring.quartz.job-store-type" "jdbc"
assert_yaml_value "${runtime_config}" "spring.quartz.jdbc.initialize-schema" "NEVER"
assert_yaml_value "${runtime_config}" "logging.level.cn.iocoder.yudao.module" "INFO"
if grep -Eq '(^|[[:space:]])(wx|rocketmq|rabbitmq|kafka|qianfan|zhipuai|openai|anthropic|stabilityai|dashscope|moonshot|deepseek):' \
    "${runtime_config}"; then
  echo "FAIL: runtime baseline must not import local third-party test integrations" >&2
  exit 1
fi
assert_yaml_value "${prod_config}" "yudao.security.mock-enable" "false"
assert_yaml_value "${prod_config}" "yudao.security.password-encoder-length" "10"
assert_yaml_value "${prod_config}" "yudao.demo" "false"
assert_yaml_value "${prod_config}" "yudao.captcha.enable" "true"
assert_yaml_value "${prod_config}" "yudao.access-log.enable" "true"
assert_yaml_value "${prod_config}" "springdoc.api-docs.enabled" "false"
assert_yaml_value "${prod_config}" "springdoc.swagger-ui.enabled" "false"
assert_yaml_value "${prod_config}" "knife4j.enable" "false"
assert_yaml_value "${prod_config}" "spring.datasource.druid.web-stat-filter.enabled" "false"
assert_yaml_value "${prod_config}" "spring.datasource.druid.stat-view-servlet.enabled" "false"
assert_yaml_value "${prod_config}" "spring.boot.admin.client.enabled" "false"
assert_yaml_value "${prod_config}" "management.endpoints.web.exposure.include" "health"
assert_yaml_value "${prod_config}" "management.endpoint.health.show-details" "never"
assert_yaml_value "${prod_config}" "yudao.sms-code.begin-code" "100000"
assert_yaml_value "${prod_config}" "yudao.sms-code.end-code" "999999"
assert_yaml_value "${prod_config}" "yudao.wxa-code.env-version" "release"
assert_yaml_value "${prod_config}" "yudao.wxa-subscribe-message.miniprogram-state" "formal"
assert_yaml_value "${base_config}" "yudao.api-encrypt.enable" '${YUDAO_API_ENCRYPT_ENABLED:false}'
for production_overlay in "${runtime_config}" "${prod_config}"; do
  if [[ -n "$(yaml_value "${production_overlay}" "yudao.api-encrypt.enable")" ]]; then
    echo "FAIL: ${production_overlay} must not override the externally controlled API-encryption setting" >&2
    exit 1
  fi
done
assert_yaml_value "${prod_config}" "mybatis-plus.encryptor.password" '${SKIT_AD_ENCRYPTION_KEY}'
assert_yaml_value "${prod_config}" "skit.ad.credential-encryption.current-key" '${SKIT_AD_CREDENTIAL_KEY}'
assert_yaml_value "${prod_config}" "skit.ad.session-token.current-key" '${SKIT_AD_SESSION_TOKEN_KEY}'
assert_yaml_value "${prod_config}" "skit.ad.callback.public-base-url" '${SKIT_AD_CALLBACK_PUBLIC_BASE_URL}'
assert_yaml_value "${prod_config}" "skit.security.client-ip.trusted-proxy-cidrs" '${SKIT_TRUSTED_PROXY_CIDRS}'
if [[ -n "$(yaml_value "${prod_config}" "skit.runtime-update.public-key")" ]] ||
   [[ -n "$(yaml_value "${base_config}" "skit.runtime-update.public-key")" ]]; then
  echo "FAIL: runtime update trust roots must be stored per tenant, not in global configuration" >&2
  exit 1
fi
assert_yaml_value "${base_config}" "skit.ad.credential-encryption.keys" "{}"
assert_yaml_value "${base_config}" "skit.ad.session-token.keys" "{}"
literal_secret_entries="$(for config_file in "${repo_root}"/yudao-server/src/main/resources/application*.yaml; do
  awk -v file="${config_file#"${repo_root}/"}" '
    {
      candidate = $0
      sub(/^[[:space:]]*#[[:space:]]*/, "", candidate)
      if (candidate !~ /^[[:space:]]*[^:]+:[[:space:]]*/) {
        next
      }
      property = candidate
      sub(/:.*/, "", property)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", property)
      normalized = tolower(property)
      if (normalized !~ /(^|[-_.])(password|secret|secretkey|token|apikey|accesskey|privatekey|clientsecret|appkey|key)$/ &&
          normalized != "customer") {
        next
      }
      value = candidate
      sub(/^[^:]*:[[:space:]]*/, "", value)
      sub(/[[:space:]]+#.*$/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      if (value != "" && value !~ /^\$\{[A-Za-z0-9_]+:?\}$/) {
        print file ":" NR ":" property
      }
    }
  ' "${config_file}"
done)"
if [[ -n "${literal_secret_entries}" ]]; then
  echo "FAIL: packaged configuration contains literal credential properties:" >&2
  printf '%s\n' "${literal_secret_entries}" >&2
  exit 1
fi
if grep -Eq '^[[:space:]]+keys:[[:space:]]*' "${prod_config}"; then
  echo "FAIL: prod overlay must not reset externally supplied retained advertising key maps" >&2
  exit 1
fi
if ! grep -Fq 'SPRING_CONFIG_IMPORT: "optional:file:/run/secrets/skit-ad-keyring.properties"' \
    <<<"${backend_service}" ||
   ! grep -Fq './ad-keyring.properties:/run/secrets/skit-ad-keyring.properties:ro' \
    <<<"${backend_service}"; then
  echo "FAIL: retained advertising keys need an optional read-only 0600 keyring import" >&2
  exit 1
fi

MYSQL_ROOT_PASSWORD=test SKIT_AD_ENCRYPTION_KEY=test-only-key-000000000000000001 \
  SKIT_AD_CREDENTIAL_KEY=test-only-credential-key-0000001 SKIT_AD_CREDENTIAL_KEY_ID=primary \
  SKIT_AD_SESSION_TOKEN_KEY=test-only-session-token-key-00001 SKIT_AD_SESSION_TOKEN_KEY_VERSION=1 \
  SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://127.0.0.1/app-api \
  SKIT_TRUSTED_PROXY_CIDRS=172.31.240.30/32 \
  docker compose -f "${compose_file}" config >/dev/null
echo "PASS: frontend topology is independent and backend ingress is proxy-only"
