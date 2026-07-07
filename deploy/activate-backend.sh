#!/usr/bin/env bash
set -euo pipefail

: "${DEPLOY_PATH:?DEPLOY_PATH is required}"
: "${IMAGE_NAME:?IMAGE_NAME is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"

cd "${DEPLOY_PATH}"

set -a
if [ -f .env ]; then
  # shellcheck disable=SC1091
  . ./.env
fi
if [ -f server.env ]; then
  # shellcheck disable=SC1091
  . ./server.env
fi
set +a

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

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  else
    docker-compose "$@"
  fi
}

mkdir -p builds images
build_package="${IMAGE_NAME}-${IMAGE_TAG}-build.tar.gz"
if [ -f "${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" ]; then
  mv "${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" "images/${IMAGE_NAME}-${IMAGE_TAG}.tar.gz"
fi

if [ -f "${build_package}" ]; then
  mv "${build_package}" "builds/${build_package}"
fi

if [ -f "builds/${build_package}" ]; then
  build_dir="builds/${IMAGE_NAME}-${IMAGE_TAG}"
  rm -rf "${build_dir}"
  mkdir -p "${build_dir}"
  tar -xzf "builds/${build_package}" -C "${build_dir}"
  docker build \
    --label app=skit-saas-backend \
    --label "org.opencontainers.image.revision=${IMAGE_TAG}" \
    -t "${IMAGE_NAME}:${IMAGE_TAG}" \
    "${build_dir}/backend-build"
elif [ -f "images/${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" ]; then
  gzip -dc "images/${IMAGE_NAME}-${IMAGE_TAG}.tar.gz" | docker load
else
  echo "Missing backend release package for ${IMAGE_NAME}:${IMAGE_TAG}"
  exit 1
fi

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
  else
    MYSQL_ROOT_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
  fi
fi

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
