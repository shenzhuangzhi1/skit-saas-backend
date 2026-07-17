#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${repo_root}/deploy/local.env"

"${repo_root}/scripts/local-stack.sh" up
source "${env_file}"

java_home="${JAVA_HOME:-}"
if [[ -x /usr/libexec/java_home ]]; then
  java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
fi
[[ -n "${java_home}" ]] || { echo "Set JAVA_HOME to a supported JDK before starting Spring Boot." >&2; exit 1; }
export JAVA_HOME="${java_home}"
export LOCAL_MYSQL_MASTER_PASSWORD
export LOCAL_MYSQL_SLAVE_PASSWORD
export SKIT_AD_ENCRYPTION_KEY
export SKIT_AD_CREDENTIAL_KEY
export SKIT_AD_SESSION_TOKEN_KEY

cd "${repo_root}"
exec mvn -pl yudao-server -am spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8"
