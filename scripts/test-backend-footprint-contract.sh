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

dormant_modules=(
  yudao-module-member yudao-module-bpm yudao-module-report yudao-module-mp
  yudao-module-pay yudao-module-mall yudao-module-crm yudao-module-erp
  yudao-module-iot yudao-module-mes yudao-module-wms yudao-module-im yudao-module-ai
)
for module in "${dormant_modules[@]}"; do
  [[ ! -e "${repo_root}/${module}" ]] || fail "dormant module must not return: ${module}"
done
dormant_stats="$(awk -F '\t' '$1 == "metric" && $2 == "dormant_module_trees" { print $3 " " $4 }' "${temp_root}/first.tsv")"
[[ "${dormant_stats}" == "0 0" ]] || fail "dormant module footprint must remain zero"

removed_artifact_pattern='yudao-module-(member|bpm|report|mp|pay|mall|product|promotion|trade|statistics|crm|erp|iot|mes|wms|im|ai)'
if git -C "${repo_root}" grep -n -E "${removed_artifact_pattern}" -- \
  pom.xml ':(glob)**/pom.xml' > "${temp_root}/removed-module-poms.txt"; then
  cat "${temp_root}/removed-module-poms.txt" >&2
  fail "Maven POM still advertises a removed module"
fi

removed_package_pattern='^import[[:space:]]+cn\.iocoder\.yudao\.module\.(member|bpm|report|mp|pay|product|promotion|trade|statistics|crm|erp|iot|mes|wms|im|ai)(\.|;)'
if git -C "${repo_root}" grep -n -E "${removed_package_pattern}" -- \
  yudao-server yudao-module-system yudao-module-infra yudao-module-skit yudao-framework \
  > "${temp_root}/removed-module-imports.txt"; then
  cat "${temp_root}/removed-module-imports.txt" >&2
  fail "active Java source imports a removed module"
fi

removed_runtime_reference_pattern='cn\.iocoder\.yudao\.module\.(member|bpm|report|mp|pay|product|promotion|trade|statistics|crm|erp|iot|mes|wms|im|ai)\.|yudao-module-(member|bpm|report|mp|pay|mall|crm|erp|iot|mes|wms|im|ai)'
if git -C "${repo_root}" grep -n -E "${removed_runtime_reference_pattern}" -- \
  yudao-server/src/main/resources \
  yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/banner \
  > "${temp_root}/removed-runtime-references.txt"; then
  cat "${temp_root}/removed-runtime-references.txt" >&2
  fail "runtime configuration still advertises a removed module"
fi

default_controller="${repo_root}/yudao-server/src/main/java/cn/iocoder/yudao/server/controller/DefaultController.java"
[[ ! -e "${default_controller}" ]] || fail "disabled-module fallback controller must not return"
removed_route_pattern='"/test"|"/admin-api/(bpm|mp|product|trade|promotion|erp|wms|crm|mes|im|report|pay|ai|iot)/\*\*"'
if git -C "${repo_root}" grep -n -E "${removed_route_pattern}" -- yudao-server/src/main/java \
  > "${temp_root}/removed-routes.txt"; then
  cat "${temp_root}/removed-routes.txt" >&2
  fail "public diagnostic or disabled-module fallback route returned"
fi

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
