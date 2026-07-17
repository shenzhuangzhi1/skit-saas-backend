#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${script_dir}/configure-public-https.sh"

bash -n "${script}"
grep -Fq 'LETSENCRYPT_EMAIL' "${script}"
grep -Fq 'SKIT_DEPLOY_PATH' "${script}"
grep -Fq 'SKIT_DEPLOY_USER' "${script}"
grep -Fq 'chown "${deploy_user}" "${environment_file}"' "${script}"
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
