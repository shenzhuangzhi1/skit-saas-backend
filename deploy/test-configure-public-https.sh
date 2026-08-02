#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${script_dir}/configure-public-https.sh"
wire_parser="${script_dir}/../yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionWireParser.java"

bash -n "${script}"
grep -Fq 'LETSENCRYPT_EMAIL' "${script}"
grep -Fq 'SKIT_DEPLOY_PATH' "${script}"
grep -Fq 'SKIT_DEPLOY_USER' "${script}"
grep -Fq 'SKIT_BACKEND_UPSTREAM' "${script}"
grep -Fq 'proxy_pass http://${callback_upstream};' "${script}"
grep -Fq 'write_callback_include() {' "${script}"
grep -Fq 'include /etc/nginx/snippets/skit-callback-proxy.conf;' "${script}"
grep -Fq 'client_header_buffer_size 64k;' "${script}"
grep -Fq 'large_client_header_buffers 4 64k;' "${script}"
grep -Fq 'proxy_connect_timeout 250ms;' "${script}"
grep -Fq 'proxy_send_timeout 250ms;' "${script}"
grep -Fq 'proxy_read_timeout 1s;' "${script}"
grep -Fq 'proxy_next_upstream off;' "${script}"
grep -Fq 'proxy_request_buffering off;' "${script}"
grep -Fq 'proxy_buffering off;' "${script}"
grep -Fq 'proxy_pass_request_body off;' "${script}"
grep -Fq 'proxy_set_header Content-Length "";' "${script}"
grep -Fq 'assert_callback_contract_limits' "${script}"
grep -Fq 'callback_request_line_and_header_bytes="65536"' "${script}"
grep -Fq 'application_raw_query_bytes="32768"' "${script}"
grep -Fq 'application_parameter_count="64"' "${script}"
grep -Fq 'application_max_value_bytes="24576"' "${script}"
grep -Fq 'public static final int MAX_WIRE_BYTES = 32768;' "${wire_parser}"
grep -Fq 'public static final int MAX_PARAMETERS = 64;' "${wire_parser}"
grep -Fq 'public static final int MAX_VALUE_BYTES = 24576;' "${wire_parser}"
if [ "$(grep -Fc 'include /etc/nginx/snippets/skit-callback-proxy.conf;' "${script}")" -ne 6 ]; then
  echo "FAIL: every Taku/Pangle callback location must use the one reusable include" >&2
  exit 1
fi
bootstrap_start="$(grep -nF 'write_http_config() {' "${script}" | cut -d: -f1)"
https_function_start="$(grep -nF 'write_https_config() {' "${script}" | cut -d: -f1)"
http_start="$(grep -nF '    listen 80;' "${script}" | tail -n 1 | cut -d: -f1)"
https_start="$(grep -nF '    listen 443 ssl;' "${script}" | tail -n 1 | cut -d: -f1)"
bootstrap_block="$(sed -n "${bootstrap_start},$((https_function_start - 1))p" "${script}")"
http_block="$(sed -n "${http_start},$((https_start - 1))p" "${script}")"
https_block="$(sed -n "${https_start},/EOF/p" "${script}")"

assert_callback_log_safety() {
  local block_name="$1"
  local callback_block="$2"

  for provider in taku pangle; do
    if ! grep -Fq "location ^~ /app-api/skit/ad-callback/${provider}/ {" <<< "${callback_block}"; then
      echo "FAIL: ${block_name} must define the ${provider} callback route" >&2
      exit 1
    fi
  done
  if [ "$(grep -Fc 'include /etc/nginx/snippets/skit-callback-proxy.conf;' <<< "${callback_block}")" -lt 2 ]; then
    echo "FAIL: ${block_name} must reuse the hardened callback include for both routes" >&2
    exit 1
  fi
}

assert_callback_log_safety "bootstrap HTTP server" "${bootstrap_block}"
assert_callback_log_safety "permanent HTTP server" "${http_block}"
assert_callback_log_safety "permanent HTTPS server" "${https_block}"
grep -Fq 'chown "${deploy_user}" "${environment_file}"' "${script}"
grep -Fq 'restore_compose_environment' "${script}"
grep -Fq 'require_container_env skit-saas-mysql MYSQL_ROOT_PASSWORD' "${script}"
grep -Fq 'require_container_env skit-saas-backend SKIT_AD_ENCRYPTION_KEY' "${script}"
grep -Fq 'require_container_env skit-saas-backend SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY_ID' "${script}"
grep -Fq 'require_container_env skit-saas-backend SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY' "${script}"
grep -Fq 'require_container_env skit-saas-backend SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY' "${script}"
grep -Fq 'The running frontend image is not a pinned GHCR image.' "${script}"
grep -Fq 'certbot/certbot:latest certonly --webroot' "${script}"
grep -Fq 'nginx:1.27-alpine' "${script}"
grep -Fq -- '--network host' "${script}"
grep -Fq '127.0.0.1:${FRONTEND_PORT:-48081}:80' "${script}"
grep -Fq 'listen 443 ssl' "${script}"
grep -Fq 'proxy_set_header X-Forwarded-Proto https' "${script}"
grep -Fq 'access_log off;' "${script}"
grep -Fq 'error_log /dev/null crit;' "${script}"
grep -Fq 'proxy_set_header X-Real-IP \$remote_addr;' "${script}"
grep -Fq 'callback_include}:/etc/nginx/snippets/skit-callback-proxy.conf:ro' "${script}"
grep -Fq '/etc/cron.d/skit-public-https-renew' "${script}"
if grep -Fq 'command -v nginx' "${script}"; then
  echo "FAIL: HTTPS provisioning must not require a host Nginx installation" >&2
  exit 1
fi

render_root="$(mktemp -d)"
render_cleanup() {
  rm -f "${render_root}/callback-proxy.inc" \
    "${render_root}/bootstrap.conf" "${render_root}/https.conf"
  rmdir "${render_root}"
}
trap render_cleanup EXIT
source "${script}"
domain="callback.example.test"
callback_upstream="127.0.0.1:48080"
frontend_upstream="127.0.0.1:48081"
callback_include="${render_root}/callback-proxy.inc"
proxy_config="${render_root}/bootstrap.conf"
assert_callback_contract_limits
write_callback_include
write_http_config
proxy_config="${render_root}/https.conf"
write_https_config

for directive in \
  'access_log off;' \
  'error_log /dev/null crit;' \
  'proxy_connect_timeout 250ms;' \
  'proxy_send_timeout 250ms;' \
  'proxy_read_timeout 1s;' \
  'proxy_next_upstream off;' \
  'proxy_request_buffering off;' \
  'proxy_buffering off;' \
  'proxy_pass_request_body off;' \
  'proxy_set_header Content-Length "";' \
  'proxy_set_header X-Real-IP $remote_addr;' \
  'proxy_set_header X-Forwarded-For $remote_addr;'; do
  grep -Fxq "${directive}" "${render_root}/callback-proxy.inc" \
    || { echo "FAIL: rendered callback include misses ${directive}" >&2; exit 1; }
done
for rendered_config in "${render_root}/bootstrap.conf" "${render_root}/https.conf"; do
  grep -Fxq 'client_header_buffer_size 64k;' "${rendered_config}"
  grep -Fxq 'large_client_header_buffers 4 64k;' "${rendered_config}"
  grep -Fq 'location ^~ /app-api/skit/ad-callback/taku/ {' "${rendered_config}"
  grep -Fq 'location ^~ /app-api/skit/ad-callback/pangle/ {' "${rendered_config}"
  grep -Fq 'proxy_pass http://127.0.0.1:48081;' "${rendered_config}"
  grep -Fq 'proxy_set_header Upgrade $http_upgrade;' "${rendered_config}"
done
[ "$(grep -Fc 'include /etc/nginx/snippets/skit-callback-proxy.conf;' \
    "${render_root}/bootstrap.conf")" -eq 2 ]
[ "$(grep -Fc 'include /etc/nginx/snippets/skit-callback-proxy.conf;' \
    "${render_root}/https.conf")" -eq 4 ]
[ "$(grep -Fc 'return 308 https://$host$request_uri;' "${render_root}/https.conf")" -eq 3 ]
echo "PASS: public HTTPS uses a Docker TLS proxy and scheduled container renewal"
