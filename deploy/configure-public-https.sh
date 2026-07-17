#!/usr/bin/env bash
set -euo pipefail

domain="${1:-}"
email="${LETSENCRYPT_EMAIL:-}"
upstream="${SKIT_FRONTEND_UPSTREAM:-127.0.0.1:80}"
nginx_root="/etc/nginx"
webroot="/var/lib/letsencrypt"

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
if [[ ! "${upstream}" =~ ^(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]]; then
  echo "SKIT_FRONTEND_UPSTREAM must be a loopback host and port." >&2
  exit 1
fi
if ! command -v nginx >/dev/null 2>&1; then
  echo "Nginx must be installed on the host before HTTPS can be configured." >&2
  exit 1
fi

if [ -d "${nginx_root}/conf.d" ]; then
  config_path="${nginx_root}/conf.d/skit-public-${domain}.conf"
elif [ -d "${nginx_root}/sites-available" ] && [ -d "${nginx_root}/sites-enabled" ]; then
  config_path="${nginx_root}/sites-available/skit-public-${domain}"
  enabled_path="${nginx_root}/sites-enabled/skit-public-${domain}"
else
  echo "Unsupported Nginx layout. Expected conf.d or sites-available/sites-enabled." >&2
  exit 1
fi

if grep -R --exclude="$(basename "${config_path}")" -E \
  "^[[:space:]]*server_name[[:space:]].*([[:space:]]|^)${domain}([[:space:];]|$)" \
  "${nginx_root}" >/dev/null 2>&1; then
  echo "Another Nginx server block already owns ${domain}; refusing to overwrite it." >&2
  exit 1
fi

install -d -m 0755 "${webroot}/.well-known/acme-challenge"

write_http_config() {
  cat > "${config_path}" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${domain};

    location ^~ /.well-known/acme-challenge/ {
        root ${webroot};
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        proxy_pass http://${upstream};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF
}

write_https_config() {
  cat > "${config_path}" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${domain};

    location ^~ /.well-known/acme-challenge/ {
        root ${webroot};
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
    ssl_session_timeout 1d;
    add_header Strict-Transport-Security "max-age=15552000" always;

    location / {
        proxy_pass http://${upstream};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
EOF
}

reload_nginx() {
  nginx -t
  if command -v systemctl >/dev/null 2>&1; then
    systemctl reload nginx
  elif command -v service >/dev/null 2>&1; then
    service nginx reload
  else
    nginx -s reload
  fi
}

if ! command -v certbot >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y certbot
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y certbot
  else
    echo "Install certbot on the host, then rerun this workflow." >&2
    exit 1
  fi
fi

write_http_config
if [ -n "${enabled_path:-}" ]; then
  ln -sfn "${config_path}" "${enabled_path}"
fi
reload_nginx

certbot certonly --webroot --webroot-path "${webroot}" --domain "${domain}" \
  --email "${email}" --agree-tos --non-interactive --keep-until-expiring

if [ ! -s "/etc/letsencrypt/live/${domain}/fullchain.pem" ] \
  || [ ! -s "/etc/letsencrypt/live/${domain}/privkey.pem" ]; then
  echo "Certificate files were not created for ${domain}." >&2
  exit 1
fi

write_https_config
reload_nginx

install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
nginx -t && systemctl reload nginx
EOF
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx

health_code="$(curl --noproxy '*' --resolve "${domain}:443:127.0.0.1" \
  --connect-timeout 10 --silent --show-error --output /dev/null --write-out '%{http_code}' \
  "https://${domain}/app-api/actuator/health")"
if [ "${health_code}" != "200" ] && [ "${health_code}" != "401" ]; then
  echo "HTTPS proxy health check returned HTTP ${health_code}." >&2
  exit 1
fi

echo "HTTPS is active for ${domain}."
