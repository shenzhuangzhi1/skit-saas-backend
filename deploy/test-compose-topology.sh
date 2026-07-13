#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"

frontend_service="$(sed -n '/^  frontend:/,/^volumes:/p' "${compose_file}")"
if grep -Eq '^    depends_on:' <<<"${frontend_service}"; then
  echo "FAIL: frontend must not depend on backend lifecycle" >&2
  exit 1
fi

MYSQL_ROOT_PASSWORD=test SKIT_AD_ENCRYPTION_KEY=test-only-key-000000000000000001 \
  docker compose -f "${compose_file}" config >/dev/null
echo "PASS: frontend Compose topology is independent"
