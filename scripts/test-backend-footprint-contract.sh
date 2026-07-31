#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
inventory="${repo_root}/scripts/backend-footprint-inventory.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

temp_root="$(mktemp -d)"
trap 'rm -rf "${temp_root}"' EXIT

"${inventory}" HEAD > "${temp_root}/first.tsv"
"${inventory}" HEAD > "${temp_root}/second.tsv"
cmp -s "${temp_root}/first.tsv" "${temp_root}/second.tsv" \
  || fail "footprint inventory is not deterministic"

expected_reactor_modules='yudao-dependencies
yudao-framework
yudao-server
yudao-module-system
yudao-module-infra
yudao-module-skit'
actual_reactor_modules="$(awk -F '\t' '$1 == "reactor_module" { print $2 }' "${temp_root}/first.tsv")"
[[ "${actual_reactor_modules}" == "${expected_reactor_modules}" ]] \
  || fail "active root reactor modules changed"

expected_server_dependencies='yudao-module-system
yudao-module-infra
yudao-module-skit'
actual_server_dependencies="$(awk -F '\t' '$1 == "server_module_dependency" { print $2 }' "${temp_root}/first.tsv")"
[[ "${actual_server_dependencies}" == "${expected_server_dependencies}" ]] \
  || fail "active server module dependencies changed"

for path in \
  yudao-module-system yudao-module-infra yudao-module-skit \
  sql/db2 sql/dm sql/highgo sql/kingbase sql/mysql sql/opengauss \
  sql/oracle sql/postgresql sql/sqlserver sql/tools; do
  [[ -d "${repo_root}/${path}" ]] || fail "protected product or database path missing: ${path}"
done

canonical_test="${repo_root}/yudao-framework/yudao-spring-boot-starter-excel/src/test/java/cn/iocoder/yudao/framework/excel/core/convert/MultiDictConvertTest.java"
[[ -f "${canonical_test}" ]] || fail "canonical MultiDictConvertTest missing"
declaration_count="$(grep -R -E '^(public[[:space:]]+)?class[[:space:]]+MultiDictConvertTest' \
  "${repo_root}/yudao-framework/yudao-spring-boot-starter-excel/src/test/java" | wc -l | tr -d '[:space:]')"
[[ "${declaration_count}" == "1" ]] || fail "MultiDictConvertTest must have exactly one top-level declaration"
grep -Fq 'assertNull(result);' "${canonical_test}" \
  || fail "unknown dictionary labels must retain the canonical null expectation"

[[ ! -e "${repo_root}/.image" ]] || fail "obsolete upstream screenshots must not return"
image_reference='.image''/'
if git -C "${repo_root}" grep -n -F "${image_reference}" > "${temp_root}/image-references.txt"; then
  cat "${temp_root}/image-references.txt" >&2
  fail "tracked files still reference obsolete upstream screenshots"
fi

echo "backend footprint contract ok"
