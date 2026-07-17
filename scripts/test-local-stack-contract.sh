#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.local.yml"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "${compose_file}" ]] || fail "local compose is missing"
grep -Fq 'name: skit-saas-local' "${compose_file}" || fail "project name missing"
grep -Fq 'mysql:8' "${compose_file}" || fail "MySQL 8 image missing"
grep -Fq 'redis:6.2-alpine' "${compose_file}" || fail "Redis 6.2 image missing"
grep -Fq '127.0.0.1:${MYSQL_PORT:-3306}:3306' "${compose_file}" || fail "MySQL is not loopback-only"
grep -Fq '127.0.0.1:${REDIS_PORT:-6379}:6379' "${compose_file}" || fail "Redis is not loopback-only"
grep -Fq 'skit-saas-local-mysql:/var/lib/mysql' "${compose_file}" || fail "MySQL volume is not isolated"
grep -Fq 'skit-saas-local-redis:/data' "${compose_file}" || fail "Redis volume is not isolated"

for file in ruoyi-vue-pro.sql skit-saas.sql quartz.sql; do
  grep -Fq "../sql/mysql/${file}:/docker-entrypoint-initdb.d/" "${compose_file}" \
    || fail "missing SQL mount ${file}"
done

grep -Fq 'container_name: skit-saas-local-mysql' "${compose_file}" \
  || fail "local MySQL container name missing"
grep -Fq 'container_name: skit-saas-local-redis' "${compose_file}" \
  || fail "local Redis container name missing"
echo "local compose contract ok"
