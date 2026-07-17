#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

command -v docker >/dev/null 2>&1 || { echo "Install Docker Desktop before backend verification." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required." >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "Install Maven 3.9+ before backend verification." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "Install Java 8+ before backend verification." >&2; exit 1; }

./deploy/test-compose-topology.sh
./deploy/test-encryption-key.sh
./deploy/test-activation-encryption-key.sh
./deploy/test-activation-health.sh
./deploy/test-release-security.sh

mvn -B -pl yudao-module-skit,yudao-module-system,yudao-module-infra -am \
  -Dtest='Skit*Test,Taku*Test,*SecurityTest,SystemPlatformAdminGuardTest,TenantVisitContextInterceptorTest,AdminAuthServiceImplTest,OAuth2TokenServiceImplTest,OAuth2TokenServiceScopedRevocationTest,TenantPackageControllerTest,TenantPackageServiceImplTest,AdminUserServiceImplTest,SpringUtilsTest,AdminServerConfigurationTest,ApiAccessLogInterceptorTest,*SecretRedactionTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

if [[ "${SKIP_INTEGRATION:-0}" != "1" ]]; then
  TESTCONTAINERS_RYUK_DISABLED=true mvn -B -pl yudao-module-skit -am \
    -Dtest=__NoSurefireTests__ \
    -Dit.test='Skit*MySqlIT' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false \
    test-compile failsafe:integration-test failsafe:verify
fi

echo "Backend local verification passed."
