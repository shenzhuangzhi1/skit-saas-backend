#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

command -v docker >/dev/null 2>&1 || { echo "Install Docker Desktop before backend verification." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required." >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "Install Maven 3.9+ before backend verification." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "Install Java 8+ before backend verification." >&2; exit 1; }
java_home="${JAVA_HOME:-}"
if [[ -x /usr/libexec/java_home ]]; then
  java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
fi
[[ -n "${java_home}" ]] || { echo "Set JAVA_HOME to a supported JDK before backend verification." >&2; exit 1; }
export JAVA_HOME="${java_home}"

./deploy/test-compose-topology.sh
./deploy/test-encryption-key.sh
./deploy/test-activation-encryption-key.sh
./deploy/test-activation-health.sh
./deploy/test-release-security.sh

mvn -B -pl yudao-module-skit -am \
  -Dtest='cn.iocoder.yudao.module.skit.service.app.SkitAppBuildMaterialServiceImplTest,cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityServiceImplTest,cn.iocoder.yudao.module.skit.controller.admin.tenant.SkitTenantBusinessControllerTest,cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoServiceTest,cn.iocoder.yudao.module.skit.framework.schema.SkitTenantBuildMaterialSchemaContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

if [[ "${SKIP_INTEGRATION:-0}" != "1" ]]; then
  TESTCONTAINERS_RYUK_DISABLED=true mvn -B -pl yudao-module-skit -am \
    -Dtest=__NoSurefireTests__ \
    -Dit.test='SkitAdAccountReadOnlyMySqlIT,SkitAdBootstrapSchemaMySqlIT,SkitAdSchemaCrossTenantPreflightMySqlIT,SkitCommissionPolicySnapshotMySqlIT,SkitAdCredentialVersionMySqlIT' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false \
    test-compile failsafe:integration-test failsafe:verify
fi

echo "Backend local verification passed."
