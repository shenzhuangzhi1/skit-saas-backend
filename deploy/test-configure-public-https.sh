#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${script_dir}/configure-public-https.sh"

bash -n "${script}"
grep -Fq 'LETSENCRYPT_EMAIL' "${script}"
grep -Fq 'certbot certonly --webroot' "${script}"
grep -Fq 'listen 443 ssl' "${script}"
grep -Fq 'proxy_set_header X-Forwarded-Proto https' "${script}"
grep -Fq 'renewal-hooks/deploy/reload-nginx' "${script}"
