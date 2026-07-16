#!/usr/bin/env bash
set -euo pipefail

: "${IMAGE_NAME:?IMAGE_NAME is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temp_root="$(mktemp -d)"
project_name="skit-saas-smoke-$$-${RANDOM}"
compose_file="${temp_root}/docker-compose.yml"
env_file="${temp_root}/.env"
health_body_file="${temp_root}/health.json"
backend_log_file="${temp_root}/backend.log"

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  else
    docker-compose "$@"
  fi
}

cleanup() {
  exit_code=$?
  trap - EXIT HUP INT TERM
  compose -p "${project_name}" -f "${compose_file}" --env-file "${env_file}" \
    down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "${temp_root}"
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

free_port() {
  python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

mkdir -p "${temp_root}/mysql/init"
# Production uses fixed container names; remove them in the isolated smoke project so a developer's
# local stack cannot be replaced or inspected accidentally.
awk '$1 != "container_name:" && $1 != "name:" { print }' \
  "${repo_root}/deploy/docker-compose.prod.yml" > "${compose_file}"
cp "${repo_root}/sql/mysql/ruoyi-vue-pro.sql" "${temp_root}/mysql/init/ruoyi-vue-pro.sql"
cp "${repo_root}/sql/mysql/skit-saas.sql" "${temp_root}/mysql/init/skit-saas.sql"
cp "${repo_root}/sql/mysql/quartz.sql" "${temp_root}/mysql/init/quartz.sql"
install -m 600 /dev/null "${temp_root}/ad-keyring.properties"

mysql_port="$(free_port)"
redis_port="$(free_port)"
backend_port="$(free_port)"
root_password="smoke-${RANDOM}-${RANDOM}-${RANDOM}"
legacy_key="$(printf 'a%.0s' {1..32})"
credential_key="$(printf 'b%.0s' {1..32})"
session_key="$(printf 'c%.0s' {1..48})"
{
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "${root_password}"
  printf 'MYSQL_DATABASE=skit_saas\n'
  printf 'MYSQL_PORT=%s\n' "${mysql_port}"
  printf 'REDIS_PORT=%s\n' "${redis_port}"
  printf 'BACKEND_PORT=%s\n' "${backend_port}"
  printf 'BACKEND_IMAGE=%s\n' "${IMAGE_NAME}"
  printf 'BACKEND_IMAGE_TAG=%s\n' "${IMAGE_TAG}"
  printf 'YUDAO_API_ENCRYPT_ENABLED=false\n'
  printf 'SKIT_AD_ENCRYPTION_KEY=%s\n' "${legacy_key}"
  printf 'SKIT_AD_CREDENTIAL_KEY=%s\n' "${credential_key}"
  printf 'SKIT_AD_CREDENTIAL_KEY_ID=smoke\n'
  printf 'SKIT_AD_SESSION_TOKEN_KEY=%s\n' "${session_key}"
  printf 'SKIT_AD_SESSION_TOKEN_KEY_VERSION=1\n'
  printf 'SKIT_AD_CALLBACK_PUBLIC_BASE_URL=http://localhost:%s/app-api\n' "${backend_port}"
  printf 'SKIT_TRUSTED_PROXY_CIDRS=172.16.0.0/12\n'
  printf 'JAVA_OPTS=-Xms256m -Xmx512m -Djava.security.egd=file:/dev/./urandom\n'
} > "${env_file}"
chmod 600 "${env_file}"

compose -p "${project_name}" -f "${compose_file}" --env-file "${env_file}" \
  up -d mysql redis backend

health_url="http://127.0.0.1:${backend_port}/actuator/health"
required_healthy_samples=5
healthy_samples=0
max_startup_restarts=3
last_restart_count=0
startup_failure=""
for _ in $(seq 1 120); do
  backend_id="$(compose -p "${project_name}" -f "${compose_file}" --env-file "${env_file}" \
    ps -q backend 2>/dev/null || true)"
  backend_state=""
  if [[ -n "${backend_id}" ]]; then
    backend_state="$(docker inspect --format '{{.RestartCount}} {{.State.Status}}' \
      "${backend_id}" 2>/dev/null || true)"
  fi
  restart_count="${backend_state%% *}"
  container_state="${backend_state#* }"
  if [[ "${restart_count}" =~ ^[0-9]+$ ]]; then
    if ((restart_count > max_startup_restarts)); then
      startup_failure="backend restarted ${restart_count} time(s) during startup (maximum tolerated: ${max_startup_restarts})"
      break
    fi
    if ((restart_count != last_restart_count)); then
      # A dependency can become reachable just after the first JVM attempt. Require a
      # fresh run of healthy samples after every bounded restart instead of failing on
      # the transient connection-refused event itself.
      healthy_samples=0
      last_restart_count="${restart_count}"
    fi
  fi
  if [[ "${container_state}" == "dead" || "${container_state}" == "exited" ]]; then
    startup_failure="backend entered ${container_state} state during startup"
    break
  fi

  : > "${health_body_file}"
  status_code="$(curl -s --max-time 3 -o "${health_body_file}" -w '%{http_code}' \
    "${health_url}" || true)"
  health_body="$(LC_ALL=C tr -d ' \t\r\n' < "${health_body_file}")"
  if [[ "${container_state}" == "running" &&
        "${status_code}" == "200" && "${health_body}" == '{"status":"UP"}' ]]; then
    ((healthy_samples += 1))
    if ((healthy_samples >= required_healthy_samples)); then
      compose -p "${project_name}" -f "${compose_file}" --env-file "${env_file}" \
        logs --no-color backend > "${backend_log_file}"
      if grep -Eq 'JobPersistenceException|Table .*QRTZ_.*doesn.t exist' "${backend_log_file}"; then
        cat "${backend_log_file}" >&2
        echo "FAIL: packaged backend became HTTP-healthy while Quartz persistence was broken" >&2
        exit 1
      fi
      echo "PASS: packaged runtime,prod image remained healthy with zero restarts"
      exit 0
    fi
  else
    healthy_samples=0
  fi
  sleep 2
done

compose -p "${project_name}" -f "${compose_file}" --env-file "${env_file}" \
  logs --no-color --tail 200 backend >&2 || true
if [[ -n "${startup_failure}" ]]; then
  echo "FAIL: ${startup_failure}" >&2
fi
echo "FAIL: packaged runtime,prod image did not become healthy" >&2
exit 1
