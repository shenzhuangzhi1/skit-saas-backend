#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.local.yml"
env_file="${repo_root}/deploy/local.env"
env_example="${repo_root}/deploy/local.env.example"
project_name="skit-saas-local"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

compose() {
  docker compose -p "${project_name}" -f "${compose_file}" --env-file "${env_file}" "$@"
}

ensure_dependencies() {
  command -v docker >/dev/null 2>&1 || die "Docker is required; install Docker Desktop and retry"
  docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required"
  [[ -f "${env_file}" ]] || cp "${env_example}" "${env_file}"
}

wait_for_health() {
  local deadline=$((SECONDS + 120))
  local mysql_state
  local redis_state
  while (( SECONDS < deadline )); do
    mysql_state="$(docker inspect --format '{{.State.Health.Status}}' skit-saas-local-mysql 2>/dev/null || true)"
    redis_state="$(docker inspect --format '{{.State.Health.Status}}' skit-saas-local-redis 2>/dev/null || true)"
    if [[ "${mysql_state}" == "healthy" && "${redis_state}" == "healthy" ]]; then
      echo "Local MySQL and Redis are healthy."
      return 0
    fi
    sleep 2
  done
  compose ps
  die "local MySQL/Redis did not become healthy within 120 seconds"
}

ensure_dependencies
case "${1:-}" in
  up)
    compose up -d mysql redis
    wait_for_health
    ;;
  down)
    compose stop mysql redis
    ;;
  status)
    compose ps
    ;;
  reset)
    [[ "${SKIT_CONFIRM_RESET:-}" == "1" ]] \
      || die "reset removes local database data; set SKIT_CONFIRM_RESET=1 to confirm"
    compose down -v --remove-orphans
    ;;
  *)
    die "usage: $0 {up|down|status|reset}"
    ;;
esac
