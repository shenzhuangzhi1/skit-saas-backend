#!/usr/bin/env bash
set -euo pipefail

: "${DEPLOY_PATH:?DEPLOY_PATH is required}"
: "${IMAGE_NAME:?IMAGE_NAME is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"

cd "${DEPLOY_PATH}"

if [ ! -f server.env ]; then
  echo "server.env is missing"
  exit 1
fi

set -a
# shellcheck disable=SC1091
. ./server.env
set +a

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"

upsert_env() {
  key="$1"
  value="$2"
  touch .env
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "${key}" "${value}" >> .env
  fi
}

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  else
    docker-compose "$@"
  fi
}

mkdir -p images
if [ -f "${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" ]; then
  mv "${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" "images/${IMAGE_NAME}-${IMAGE_TAG}.tar.gz"
fi

gzip -dc "images/${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" | docker load

upsert_env MYSQL_ROOT_PASSWORD "${MYSQL_ROOT_PASSWORD}"
upsert_env MYSQL_DATABASE "${MYSQL_DATABASE:-skit_saas}"
upsert_env MYSQL_PORT "${MYSQL_PORT:-3306}"
upsert_env REDIS_PORT "${REDIS_PORT:-6379}"
upsert_env BACKEND_PORT "${BACKEND_PORT:-48080}"
upsert_env FRONTEND_PORT "${FRONTEND_PORT:-80}"
upsert_env BACKEND_IMAGE_TAG "${IMAGE_TAG}"

compose -f docker-compose.prod.yml --env-file .env up -d mysql redis backend

for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:${BACKEND_PORT:-48080}/actuator/health" >/dev/null; then
    docker ps --filter name=skit-saas-backend
    exit 0
  fi
  sleep 2
done

docker logs --tail 120 skit-saas-backend || true
exit 1
