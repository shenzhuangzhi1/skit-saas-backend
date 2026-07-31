#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

requested_ref="${1:-HEAD}"
resolved_ref="$(git rev-parse --verify "${requested_ref}^{commit}")"

active_paths=(
  yudao-dependencies
  yudao-framework
  yudao-server
  yudao-module-system
  yudao-module-infra
  yudao-module-skit
)

dormant_paths=(
  yudao-module-member
  yudao-module-bpm
  yudao-module-report
  yudao-module-mp
  yudao-module-pay
  yudao-module-mall
  yudao-module-crm
  yudao-module-erp
  yudao-module-iot
  yudao-module-mes
  yudao-module-wms
  yudao-module-im
  yudao-module-ai
)

tree_stats() {
  git ls-tree -r -l "${resolved_ref}" -- "$@" \
    | awk '{ files += 1; bytes += $4 } END { printf "%d\t%.0f", files, bytes }'
}

repository_stats() {
  git ls-tree -r -l "${resolved_ref}" \
    | awk '{ files += 1; bytes += $4 } END { printf "%d\t%.0f", files, bytes }'
}

print_metric() {
  local name="$1"
  shift
  printf 'metric\t%s\t%s\n' "${name}" "$(tree_stats "$@")"
}

printf 'ref\t%s\n' "${resolved_ref}"
printf 'columns\ttype\tname\tfiles\tbytes\n'
printf 'metric\trepository\t%s\n' "$(repository_stats)"
print_metric active_product_trees "${active_paths[@]}"
print_metric dormant_module_trees "${dormant_paths[@]}"
print_metric upstream_screenshots .image
print_metric database_sql sql
print_metric documentation docs
print_metric deployment deploy
print_metric readme README.md

for path in "${dormant_paths[@]}"; do
  print_metric "${path}" "${path}"
done

git show "${resolved_ref}:pom.xml" \
  | sed -n '/<modules>/,/<\/modules>/p' \
  | grep '<module>' \
  | grep -v '<!--' \
  | sed -E 's#.*<module>([^<]+)</module>.*#reactor_module\t\1#'

git show "${resolved_ref}:yudao-server/pom.xml" \
  | grep '<artifactId>yudao-module-' \
  | grep -v '<!--' \
  | sed -E 's#.*<artifactId>([^<]+)</artifactId>.*#server_module_dependency\t\1#'
