#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${repo_root}/deploy/local.env"

"${repo_root}/scripts/local-stack.sh" up
source "${env_file}"

export LOCAL_MYSQL_MASTER_PASSWORD
export LOCAL_MYSQL_SLAVE_PASSWORD
export SKIT_AD_ENCRYPTION_KEY
export SKIT_AD_CREDENTIAL_KEY
export SKIT_AD_SESSION_TOKEN_KEY

cd "${repo_root}"
exec mvn -pl yudao-server -am spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8"
