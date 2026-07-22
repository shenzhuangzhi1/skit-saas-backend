#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"
base_config="${repo_root}/yudao-server/src/main/resources/application.yaml"
runtime_config="${repo_root}/yudao-server/src/main/resources/application-runtime.yaml"
prod_config="${repo_root}/yudao-server/src/main/resources/application-prod.yaml"
startup_smoke="${repo_root}/deploy/test-production-image-startup.sh"
server_pom="${repo_root}/yudao-server/pom.xml"
activation_script="${repo_root}/deploy/activate-backend.sh"
workflow="${repo_root}/.github/workflows/cicd.yml"

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
if ! grep -Fq '127.0.0.1:${FRONTEND_PORT:-48081}:80' <<<"${frontend_service}"; then
  echo "FAIL: frontend host port must bind loopback so public traffic enters through the TLS proxy" >&2
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
for required_startup_guard in \
    "trap 'exit 129' HUP" \
    "trap 'exit 130' INT" \
    "trap 'exit 143' TERM" \
    'required_healthy_samples=5' \
    'max_startup_restarts=3' \
    "'{{.RestartCount}} {{.State.Status}}'"; do
  if ! grep -Fq -- "${required_startup_guard}" "${startup_smoke}"; then
    echo "FAIL: packaged startup smoke is missing guard ${required_startup_guard}" >&2
    exit 1
  fi
done
if ! grep -Fq '<artifactId>spring-boot-starter-actuator</artifactId>' "${server_pom}"; then
  echo "FAIL: packaged server must include Actuator for the production health gate" >&2
  exit 1
fi
if ! grep -Fq './mysql/init/quartz.sql:/docker-entrypoint-initdb.d/03-quartz.sql:ro' \
    "${compose_file}"; then
  echo "FAIL: fresh production databases must initialize the Quartz scheduler schema" >&2
  exit 1
fi
for quartz_release_contract in \
    "${startup_smoke}|sql/mysql/quartz.sql" \
    "${startup_smoke}|JobPersistenceException" \
    "${startup_smoke}|max_startup_restarts=3" \
    "${activation_script}|docker-compose.prod.yml ruoyi-vue-pro.sql skit-saas.sql quartz.sql" \
    "${activation_script}|information_schema.TABLES" \
    "${activation_script}|information_schema.COLUMNS" \
    "${activation_script}|information_schema.TABLE_CONSTRAINTS" \
    "${activation_script}|quartz_schema_state_after" \
    "${activation_script}|required_healthy_samples=5" \
    "${activation_script}|max_startup_restarts=3" \
    "${activation_script}|up -d --no-deps --force-recreate backend" \
    "${activation_script}|JobPersistenceException" \
    "${activation_script}|< mysql/init/quartz.sql" \
    "${repo_root}/deploy/test-activation-encryption-key.sh|MYSQL_SECRET_ARG_SENTINEL" \
    "${workflow}|.deploy/quartz.sql"; do
  contract_file="${quartz_release_contract%%|*}"
  contract_text="${quartz_release_contract#*|}"
  if ! grep -Fq -- "${contract_text}" "${contract_file}"; then
    echo "FAIL: Quartz release contract is missing ${contract_text} in ${contract_file}" >&2
    exit 1
  fi
done
for skit_schema_summary_contract in \
    "${activation_script}|skit_ad_network_capability" \
    "${activation_script}|skit_content_entitlement" \
    "${activation_script}|EXTRA" \
    "${activation_script}|SELECT 'INDEXES' AS kind" \
    "${activation_script}|information_schema.STATISTICS" \
    "${activation_script}|SELECT 'CHECKS' AS kind" \
    "${activation_script}|information_schema.CHECK_CONSTRAINTS"; do
  contract_file="${skit_schema_summary_contract%%|*}"
  contract_text="${skit_schema_summary_contract#*|}"
  if ! grep -Fq -- "${contract_text}" "${contract_file}"; then
    echo "FAIL: Skit production schema summary is missing ${contract_text}" >&2
    exit 1
  fi
done
for network_capability_error_diagnostic_contract in \
    "${activation_script}|print_network_capability_error_diagnostic" \
    "${activation_script}|infra_api_error_log" \
    "${activation_script}|request_method" \
    "${activation_script}|request_url" \
    "${activation_script}|/admin-api/skit/tenant/ad-readiness/network-capability" \
    "${activation_script}|exception_name" \
    "${activation_script}|exception_root_cause_message" \
    "${activation_script}|root_cause_type" \
    "${activation_script}|REGEXP" \
    "${activation_script}|NOT REGEXP '[[:cntrl:]]'" \
    "${activation_script}|<redacted>" \
    "${activation_script}|MAX_EXECUTION_TIME(2000)" \
    "${activation_script}|DESC LIMIT 500" \
    "${activation_script}|metadata query failed safely" \
    "${activation_script}|exception_class_name" \
    "${activation_script}|exception_method_name" \
    "${activation_script}|exception_line_number" \
    "${activation_script}|exception_time" \
    "${activation_script}|DESC LIMIT 1"; do
  contract_file="${network_capability_error_diagnostic_contract%%|*}"
  contract_text="${network_capability_error_diagnostic_contract#*|}"
  if ! grep -Fq -- "${contract_text}" "${contract_file}"; then
    echo "FAIL: safe network-capability error diagnostic is missing ${contract_text}" >&2
    exit 1
  fi
done
if grep -Fq -- 'if ! print_network_capability_error_diagnostic' "${activation_script}"; then
  echo "FAIL: a best-effort error diagnostic must not gate healthy backend activation" >&2
  exit 1
fi
network_capability_error_diagnostic="$({
  sed -n '/^print_network_capability_error_diagnostic() {$/,/^}$/p' "${activation_script}"
} || true)"
for forbidden_error_detail in \
    'request_params' \
    'exception_message' \
    'exception_stack_trace' \
    'user_ip' \
    'user_agent'; do
  if grep -Fq -- "${forbidden_error_detail}" <<<"${network_capability_error_diagnostic}"; then
    echo "FAIL: network-capability error diagnostic exposes ${forbidden_error_detail}" >&2
    exit 1
  fi
done
if grep -Fq -- 'docker_cmd logs --since' "${activation_script}"; then
  echo "FAIL: a forced-new backend must be validated from its complete startup log" >&2
  exit 1
fi
for global_wx_auto_configuration in \
    'com.binarywang.spring.starter.wxjava.mp.config.WxMpAutoConfiguration' \
    'com.binarywang.spring.starter.wxjava.miniapp.config.WxMaAutoConfiguration'; do
  if ! grep -Fq -- "- ${global_wx_auto_configuration}" "${runtime_config}"; then
    echo "FAIL: production must disable the global, non-tenant-safe ${global_wx_auto_configuration}" >&2
    exit 1
  fi
done
assert_yaml_value "${runtime_config}" "server.port" "48080"
assert_yaml_value "${runtime_config}" "spring.datasource.dynamic.primary" "master"
assert_yaml_value "${runtime_config}" "spring.redis.host" '${REDIS_HOST:redis}'
assert_yaml_value "${runtime_config}" "spring.redis.port" '${REDIS_PORT:6379}'
assert_yaml_value "${runtime_config}" "spring.quartz.job-store-type" "jdbc"
assert_yaml_value "${runtime_config}" "spring.quartz.jdbc.initialize-schema" "NEVER"
assert_yaml_value "${runtime_config}" "logging.level.cn.iocoder.yudao.module" "INFO"
assert_yaml_value "${runtime_config}" "wx.mp.config-storage.type" "RedisTemplate"
assert_yaml_value "${runtime_config}" "wx.mp.config-storage.http-client-type" "HttpComponents"
assert_yaml_value "${runtime_config}" "wx.miniapp.config-storage.type" "RedisTemplate"
assert_yaml_value "${runtime_config}" "wx.miniapp.config-storage.http-client-type" "HttpComponents"
if grep -Eq '(^|[[:space:]])(rocketmq|rabbitmq|kafka|qianfan|zhipuai|openai|anthropic|stabilityai|dashscope|moonshot|deepseek):' \
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
assert_yaml_value "${prod_config}" "management.endpoint.health.probes.enabled" "false"
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
