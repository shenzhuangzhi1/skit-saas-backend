#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected="${repo_root}/docs/backend-packaged-dependencies.txt"
jar_path="${1:-${repo_root}/yudao-server/target/yudao-server.jar}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "${expected}" ]] || fail "packaged dependency baseline is missing"

temp_root="$(mktemp -d)"
trap 'rm -rf "${temp_root}"' EXIT
actual="${temp_root}/packaged-dependencies.txt"
"${repo_root}/scripts/backend-packaged-dependencies.sh" "${jar_path}" > "${actual}"

diff -u "${expected}" "${actual}" \
  || fail "packaged runtime dependencies differ from the recorded inventory"

for module in yudao-module-system yudao-module-infra yudao-module-skit; do
  grep -Eq "^BOOT-INF/lib/${module}-[^/]+\\.jar$" "${actual}" \
    || fail "required product module missing from packaged JAR: ${module}"
done

removed_artifact_pattern='yudao-module-(member|bpm|report|mp|pay|mall|product|promotion|trade|statistics|crm|erp|iot|mes|wms|im|ai)'
if grep -E "${removed_artifact_pattern}" "${actual}"; then
  fail "packaged JAR contains a removed dormant module"
fi

dependency_count="$(wc -l < "${actual}" | tr -d '[:space:]')"
[[ "${dependency_count}" -gt 0 ]] || fail "packaged dependency inventory is empty"
echo "packaged dependency contract ok (${dependency_count} JARs)"
