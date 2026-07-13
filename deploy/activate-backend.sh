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

DOCKER_USE_SUDO=0
DOCKER_SUDO_PASSWORD=0

sudo_cmd() {
  if [ "${DOCKER_SUDO_PASSWORD}" = "1" ]; then
    printf '%s\n' "${SUDO_PASSWORD}" | sudo -S -p '' "$@"
  else
    sudo -n "$@"
  fi
}

docker_cmd() {
  if [ "${DOCKER_USE_SUDO}" = "1" ]; then
    if [ -n "${DOCKER_CONFIG:-}" ]; then
      sudo_cmd env DOCKER_CONFIG="${DOCKER_CONFIG}" docker "$@"
    else
      sudo_cmd docker "$@"
    fi
  else
    docker "$@"
  fi
}

compose_cmd() {
  if [ "${DOCKER_USE_SUDO}" = "1" ]; then
    if [ -n "${DOCKER_CONFIG:-}" ]; then
      sudo_cmd env DOCKER_CONFIG="${DOCKER_CONFIG}" docker-compose "$@"
    else
      sudo_cmd docker-compose "$@"
    fi
  else
    docker-compose "$@"
  fi
}

prepare_docker_access() {
  if docker version >/dev/null 2>&1; then
    return
  fi
  if command -v sudo >/dev/null 2>&1 && sudo -n docker version >/dev/null 2>&1; then
    DOCKER_USE_SUDO=1
    return
  fi
  if [ -n "${SUDO_PASSWORD:-}" ] && command -v sudo >/dev/null 2>&1 \
    && printf '%s\n' "${SUDO_PASSWORD}" | sudo -S -p '' docker version >/dev/null 2>&1; then
    DOCKER_USE_SUDO=1
    DOCKER_SUDO_PASSWORD=1
    return
  fi
  echo "Docker is not accessible for this SSH user. Add the user to the docker group or configure sudo access."
  exit 1
}

compose() {
  if docker_cmd compose version >/dev/null 2>&1; then
    docker_cmd compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    compose_cmd "$@"
  else
    echo "Docker Compose is not installed."
    exit 1
  fi
}

prepare_docker_access

docker_config=""
if [ -n "${GHCR_TOKEN:-}" ]; then
  docker_config="$(mktemp -d)"
  export DOCKER_CONFIG="${docker_config}"
  printf '%s' "${GHCR_TOKEN}" | docker_cmd login ghcr.io -u "${GHCR_USERNAME:-github-actions}" --password-stdin
  trap 'rm -rf "${docker_config}"' EXIT
fi

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
  else
    MYSQL_ROOT_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
  fi
fi

# Generate the advertising credential key once, then reuse the persisted value on every release.
# An operator may inject SKIT_AD_ENCRYPTION_KEY before the first deployment to use a managed Secret.
if [ -z "${SKIT_AD_ENCRYPTION_KEY:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    SKIT_AD_ENCRYPTION_KEY="$(openssl rand -hex 16)"
  else
    SKIT_AD_ENCRYPTION_KEY="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
  fi
fi
case "${#SKIT_AD_ENCRYPTION_KEY}" in
  16|24|32) ;;
  *)
    echo "SKIT_AD_ENCRYPTION_KEY must contain exactly 16, 24, or 32 single-byte characters."
    exit 1
    ;;
esac
if [[ ! "${SKIT_AD_ENCRYPTION_KEY}" =~ ^[A-Za-z0-9._+/=-]+$ ]]; then
  echo "SKIT_AD_ENCRYPTION_KEY contains unsafe characters for server-side environment persistence."
  exit 1
fi

upsert_env MYSQL_ROOT_PASSWORD "${MYSQL_ROOT_PASSWORD}"
upsert_env SKIT_AD_ENCRYPTION_KEY "${SKIT_AD_ENCRYPTION_KEY}"
upsert_env MYSQL_DATABASE "${MYSQL_DATABASE:-skit_saas}"
upsert_env MYSQL_PORT "${MYSQL_PORT:-3306}"
upsert_env REDIS_PORT "${REDIS_PORT:-6379}"
upsert_env BACKEND_PORT "${BACKEND_PORT:-48080}"
upsert_env BACKEND_HEALTH_PATH "${BACKEND_HEALTH_PATH:-/actuator/health}"
upsert_env FRONTEND_PORT "${FRONTEND_PORT:-80}"
upsert_env BACKEND_IMAGE "${IMAGE_NAME}"
upsert_env BACKEND_IMAGE_TAG "${IMAGE_TAG}"

compose -f docker-compose.prod.yml --env-file .env pull backend
compose -f docker-compose.prod.yml --env-file .env up -d mysql redis backend

health_url="http://127.0.0.1:${BACKEND_PORT:-48080}${BACKEND_HEALTH_PATH:-/actuator/health}"
for _ in $(seq 1 90); do
  status_code="$(curl -sS -o /dev/null -w '%{http_code}' "${health_url}" || true)"
  if [ "${status_code}" -ge 200 ] 2>/dev/null && [ "${status_code}" -lt 500 ]; then
    docker_cmd ps --filter name=skit-saas-backend
    exit 0
  fi
  sleep 2
done

docker_cmd logs --tail 120 skit-saas-backend || true
exit 1
