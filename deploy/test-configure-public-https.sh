#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${script_dir}/configure-public-https.sh"

bash -n "${script}"
grep -Fq 'LETSENCRYPT_EMAIL' "${script}"
grep -Fq 'SKIT_DEPLOY_PATH' "${script}"
grep -Fq 'SKIT_DEPLOY_USER' "${script}"
grep -Fq 'SKIT_BACKEND_UPSTREAM' "${script}"
grep -Fq 'proxy_pass http://${callback_upstream};' "${script}"
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
  if [ "$(grep -Fc 'access_log off;' <<< "${callback_block}")" -lt 2 ]; then
    echo "FAIL: ${block_name} must disable access logs for both callback routes" >&2
    exit 1
  fi
  if [ "$(grep -Fc 'error_log /dev/null crit;' <<< "${callback_block}")" -lt 2 ]; then
    echo "FAIL: ${block_name} must suppress URI-bearing errors for both callback routes" >&2
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
grep -Fq 'The running frontend image is not a pinned GHCR image.' "${script}"
grep -Fq 'certbot/certbot:latest certonly --webroot' "${script}"
grep -Fq 'nginx:1.27-alpine' "${script}"
grep -Fq -- '--network host' "${script}"
grep -Fq '127.0.0.1:${FRONTEND_PORT:-48081}:80' "${script}"
grep -Fq 'listen 443 ssl' "${script}"
grep -Fq 'proxy_set_header X-Forwarded-Proto https' "${script}"
grep -Fq '/etc/cron.d/skit-public-https-renew' "${script}"
if grep -Fq 'command -v nginx' "${script}"; then
  echo "FAIL: HTTPS provisioning must not require a host Nginx installation" >&2
  exit 1
fi
echo "PASS: public HTTPS uses a Docker TLS proxy and scheduled container renewal"
