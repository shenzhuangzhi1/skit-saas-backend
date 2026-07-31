#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_path="${1:-${repo_root}/yudao-server/target/yudao-server.jar}"

if [[ "${jar_path}" != /* ]]; then
  jar_path="${repo_root}/${jar_path}"
fi

[[ -f "${jar_path}" ]] || {
  echo "Packaged server JAR not found: ${jar_path}" >&2
  echo "Run: mvn -B -pl yudao-server -am -DskipTests package" >&2
  exit 1
}
command -v jar >/dev/null 2>&1 || {
  echo "The JDK jar command is required to inspect packaged dependencies." >&2
  exit 1
}

LC_ALL=C jar tf "${jar_path}" \
  | LC_ALL=C sort -u \
  | grep -E '^BOOT-INF/lib/[^/]+\.jar$'
