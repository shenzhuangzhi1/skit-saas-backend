#!/usr/bin/env bash
set -euo pipefail

domain="${1:-}"
email="${LETSENCRYPT_EMAIL:-}"
deploy_path="${SKIT_DEPLOY_PATH:-skit-saas}"
deploy_user="${SKIT_DEPLOY_USER:-}"
frontend_upstream="${SKIT_FRONTEND_UPSTREAM:-127.0.0.1:48081}"
callback_upstream="${SKIT_BACKEND_UPSTREAM:-127.0.0.1:48080}"
frontend_port="48081"
backend_port="48080"
proxy_name="skit-public-https"
proxy_root="/opt/${proxy_name}"
webroot="${proxy_root}/webroot"
proxy_config="${proxy_root}/server.conf"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this script as root through the controlled deployment workflow." >&2
  exit 1
fi
if [[ ! "${domain}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$ ]] \
  || [[ "${domain}" == *..* ]]; then
  echo "A single DNS hostname is required." >&2
  exit 1
fi
if [[ ! "${email}" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "LETSENCRYPT_EMAIL must be a valid certificate contact address." >&2
  exit 1
fi
if [ "${frontend_upstream}" != "127.0.0.1:${frontend_port}" ]; then
  echo "SKIT_FRONTEND_UPSTREAM must be 127.0.0.1:${frontend_port}." >&2
  exit 1
fi
if [ "${callback_upstream}" != "127.0.0.1:${backend_port}" ]; then
  echo "SKIT_BACKEND_UPSTREAM must be 127.0.0.1:${backend_port}." >&2
  exit 1
fi
if [[ ! "${deploy_path}" =~ ^/?[A-Za-z0-9][A-Za-z0-9._/-]{0,240}$ ]] \
  || [[ "${deploy_path}" == *".."* ]]; then
  echo "SKIT_DEPLOY_PATH must be a safe deployment directory." >&2
  exit 1
fi
if [ ! -d "${deploy_path}" ] || [ -L "${deploy_path}" ]; then
  echo "SKIT_DEPLOY_PATH does not identify a regular deployment directory." >&2
  exit 1
fi
if [[ ! "${deploy_user}" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || ! id "${deploy_user}" >/dev/null 2>&1; then
  echo "SKIT_DEPLOY_USER must identify the deployment SSH user." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1 || ! docker version >/dev/null 2>&1; then
  echo "Docker must be available to configure public HTTPS." >&2
  exit 1
fi

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    echo "Docker Compose is required to move the frontend behind the TLS proxy." >&2
    exit 1
  fi
}

upsert_env() {
  local key="$1"
  local value="$2"
  local environment_file="${deploy_path}/.env"
  local staged_file
  staged_file="$(mktemp "${deploy_path}/.env.XXXXXX")"
  if [ -f "${environment_file}" ]; then
    grep -v "^${key}=" "${environment_file}" > "${staged_file}" || true
  fi
  printf '%s=%q\n' "${key}" "${value}" >> "${staged_file}"
  chmod 600 "${staged_file}"
  mv "${staged_file}" "${environment_file}"
  chown "${deploy_user}" "${environment_file}"
}

container_env_value() {
  local container="$1"
  local key="$2"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${container}" \
    | sed -n "s/^${key}=//p" | head -n 1
}

require_container_env() {
  local container="$1"
  local key="$2"
  local value
  value="$(container_env_value "${container}" "${key}")"
  if [ -z "${value}" ]; then
    echo "The running ${container} container does not expose required ${key}." >&2
    exit 1
  fi
  upsert_env "${key}" "${value}"
}

restore_compose_environment() {
  local frontend_image
  local frontend_repository
  local frontend_tag
  frontend_image="$(docker inspect --format '{{.Config.Image}}' skit-saas-frontend)"
  if [[ ! "${frontend_image}" =~ ^ghcr\.io/[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+$ ]]; then
    echo "The running frontend image is not a pinned GHCR image." >&2
    exit 1
  fi
  frontend_repository="${frontend_image%:*}"
  frontend_tag="${frontend_image##*:}"
  upsert_env FRONTEND_IMAGE "${frontend_repository}"
  upsert_env FRONTEND_IMAGE_TAG "${frontend_tag}"
  require_container_env skit-saas-mysql MYSQL_ROOT_PASSWORD
  require_container_env skit-saas-mysql MYSQL_DATABASE
  require_container_env skit-saas-backend SKIT_AD_ENCRYPTION_KEY
  require_container_env skit-saas-backend SKIT_AD_CREDENTIAL_KEY
  require_container_env skit-saas-backend SKIT_AD_CREDENTIAL_KEY_ID
  require_container_env skit-saas-backend SKIT_AD_SESSION_TOKEN_KEY
  require_container_env skit-saas-backend SKIT_AD_SESSION_TOKEN_KEY_VERSION
  require_container_env skit-saas-backend SKIT_AD_CALLBACK_PUBLIC_BASE_URL
}

acquire_deploy_lock() {
  if command -v flock >/dev/null 2>&1; then
    exec 9>"${deploy_path}/.deploy.lock"
    if ! flock -w 900 9; then
      echo "Another activation holds ${deploy_path}/.deploy.lock." >&2
      exit 1
    fi
  fi
}

write_http_config() {
  cat > "${proxy_config}" <<EOF
map \$http_upgrade \$connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${domain};

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/certbot;
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        proxy_pass http://${frontend_upstream};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
    }
}
EOF
}

write_https_config() {
  cat > "${proxy_config}" <<EOF
map \$http_upgrade \$connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${domain};

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/certbot;
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        return 308 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name ${domain};

    ssl_certificate /etc/letsencrypt/live/${domain}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${domain}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    add_header Strict-Transport-Security "max-age=15552000" always;

    location ^~ /app-api/skit/ad-callback/taku/ {
        access_log off;
        proxy_pass http://${callback_upstream};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Proto https;
    }

    location / {
        proxy_pass http://${frontend_upstream};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
    }
}
EOF
}

start_proxy() {
  if docker inspect "${proxy_name}" >/dev/null 2>&1; then
    owner="$(docker inspect --format '{{ index .Config.Labels "app" }}' "${proxy_name}")"
    if [ "${owner}" != "${proxy_name}" ]; then
      echo "Refusing to replace an unrelated ${proxy_name} container." >&2
      exit 1
    fi
    docker rm -f "${proxy_name}" >/dev/null
  fi
  docker run -d --name "${proxy_name}" --network host --restart unless-stopped \
    --label "app=${proxy_name}" \
    -v "${proxy_config}:/etc/nginx/conf.d/default.conf:ro" \
    -v "${webroot}:/var/www/certbot:ro" \
    -v /etc/letsencrypt:/etc/letsencrypt:ro \
    nginx:1.27-alpine >/dev/null
  docker exec "${proxy_name}" nginx -t >/dev/null
}

acquire_deploy_lock
compose_file="${deploy_path}/docker-compose.prod.yml"
if [ ! -f "${compose_file}" ] || [ -L "${compose_file}" ]; then
  echo "The deployed Docker Compose file is missing or unsafe." >&2
  exit 1
fi
if grep -Fq '      - "${FRONTEND_PORT:-80}:80"' "${compose_file}"; then
  sed -i 's|      - "${FRONTEND_PORT:-80}:80"|      - "127.0.0.1:${FRONTEND_PORT:-48081}:80"|' "${compose_file}"
elif ! grep -Fq '      - "127.0.0.1:${FRONTEND_PORT:-48081}:80"' "${compose_file}"; then
  echo "The deployed frontend port mapping is not recognized." >&2
  exit 1
fi
restore_compose_environment
upsert_env FRONTEND_PORT "${frontend_port}"

(
  cd "${deploy_path}"
  compose -f docker-compose.prod.yml --env-file .env up -d --no-deps --force-recreate frontend
)
frontend_ready=0
for _ in $(seq 1 60); do
  if curl --fail --silent --show-error "http://${frontend_upstream}/" >/dev/null; then
    frontend_ready=1
    break
  fi
  sleep 2
done
if [ "${frontend_ready}" != "1" ]; then
  echo "The loopback frontend did not become ready after the port migration." >&2
  exit 1
fi

install -d -m 0755 "${webroot}/.well-known/acme-challenge"
write_http_config
start_proxy

docker run --rm \
  -v "${webroot}:/var/www/certbot" \
  -v /etc/letsencrypt:/etc/letsencrypt \
  certbot/certbot:latest certonly --webroot --webroot-path /var/www/certbot \
  --domain "${domain}" --email "${email}" --agree-tos --non-interactive --keep-until-expiring

if [ ! -s "/etc/letsencrypt/live/${domain}/fullchain.pem" ] \
  || [ ! -s "/etc/letsencrypt/live/${domain}/privkey.pem" ]; then
  echo "Certificate files were not created for ${domain}." >&2
  exit 1
fi

write_https_config
docker exec "${proxy_name}" nginx -t >/dev/null
docker exec "${proxy_name}" nginx -s reload >/dev/null

cat > /etc/cron.d/skit-public-https-renew <<EOF
SHELL=/bin/sh
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
17 3 * * * root docker run --rm -v ${webroot}:/var/www/certbot -v /etc/letsencrypt:/etc/letsencrypt certbot/certbot:latest renew --quiet && docker exec ${proxy_name} nginx -s reload >> /var/log/${proxy_name}-renew.log 2>&1
EOF
chmod 644 /etc/cron.d/skit-public-https-renew

health_code="$(curl --noproxy '*' --resolve "${domain}:443:127.0.0.1" \
  --connect-timeout 10 --silent --show-error --output /dev/null --write-out '%{http_code}' \
  "https://${domain}/app-api/actuator/health")"
if [ "${health_code}" != "200" ] && [ "${health_code}" != "401" ]; then
  echo "HTTPS proxy health check returned HTTP ${health_code}." >&2
  exit 1
fi

echo "HTTPS is active for ${domain}."
