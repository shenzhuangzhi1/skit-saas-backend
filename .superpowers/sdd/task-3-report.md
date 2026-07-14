# Task 3 report: transactional global invite ownership

Status: implementation, required verification, and independent review complete.

## Scope

- Added `skit_invite_code_registry` domain, narrow mapper, and registry service as the only invitation-code ownership authority.
- Kept global normalized-code discovery inside the minimum tenant-ignore scope, then re-entered the resolved tenant for every owner read, lock, and write.
- Changed agent creation and root-code rotation to claim and transition registry ownership inside the existing `@DSTransactional` boundary.
- Changed member registration to use registry-first discovery, server-issued opaque App-context tenant mismatch rejection, owner-to-registry lock order, exact tuple revalidation, and fail-closed cross-tenant handling.
- Changed public invitation resolution to use active registry ownership and tenant-local owner/agent revalidation without accepting tenant input from the request.
- Kept retired and disabled codes permanently claimed; rotation never rewrites inviter or closure history.

## RED evidence

- `/tmp/task3-agent-red.log`, `/tmp/task3-member-red2.log`, `/tmp/task3-member-red3.log`, and `/tmp/task3-member-corrupt-red.log`: agent/member services initially lacked registry-only ownership, mismatch short-circuiting, exact locked-owner validation, and corrupt-owner rejection.
- `/tmp/task3-invite-mysql-attempt1.log`: real MySQL plus the tenant parser exposed invalid `LIMIT 1 FOR UPDATE` rewriting.
- `/tmp/task3-invite-mysql-attempt2.log` and `/tmp/task3-invite-mysql-attempt3.log`: the first real MyBatis test context omitted the production metadata handler, exposing missing audit timestamps.
- `/tmp/task3-resolve-tenant-red.log`: public invitation view hydration read tenant-local agent/tenant data after the tenant context had been restored.
- `/tmp/task3-ds-proxy-red3.log`: with the real `@DSTransactional` advice but a non-routing test DataSource, a forced final agent update failure left the old registry code incorrectly committed as `ROTATED`; this established the connection-proxy rollback requirement.

## GREEN evidence

- Focused registry/agent/member unit suite: `/tmp/task3-focused-stable.log` — 61 tests, 0 failures, 0 errors.
- Full Skit unit regression: `/tmp/task3-all-skit-unit-final.log` — 156 tests, 0 failures, 0 errors.
- Task 3 real MySQL suite with Spring/MyBatis tenant interception, a real Spring `@Transactional` member proxy, and a real dynamic-datasource `@DSTransactional` agent proxy: `/tmp/task3-invite-mysql-ds-final.log` — 10 tests, 0 failures, 0 errors.
- Exact full MySQL selector: `/tmp/task3-all-mysql-final.log` — 40 tests, 0 failures, 0 errors.
- Focused dynamic-datasource rollback rerun: `/tmp/task3-ds-proxy-green.log` — forced failure leaves the agent root unchanged, the old registry row `ACTIVE`, and no additional registry row.
- Public resolution tenant-context rerun: `/tmp/task3-resolve-tenant-green.log` — tenant-local hydration stays inside `TenantUtils.execute` and restores the caller context.

The MySQL suite proves 20-way mixed AGENT/MEMBER claim contention has one committed owner, case variants collide, terminal codes cannot be rebound or have their owner tuple changed, registration/rotation outcomes are linearizable, concurrent rotations leave one active code equal to the agent root, owner/claim/closure failures roll back atomically, corrupt cross-tenant registry rows fail closed, and existing inviter/closure history remains unchanged.

## Commands

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/var/homebrew/tmp/.cellar/maven/3.9.16/libexec/bin/mvn \
  -pl yudao-module-skit -DskipTests=false \
  -Dtest='SkitInviteCodeRegistryServiceImplTest,SkitAgentServiceImplTest,SkitMemberServiceImplTest' test

JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/var/homebrew/tmp/.cellar/maven/3.9.16/libexec/bin/mvn \
  -pl yudao-module-skit -DskipTests=false test

JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/var/homebrew/tmp/.cellar/maven/3.9.16/libexec/bin/mvn \
  -pl yudao-module-skit -DskipTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=__NoSurefireTests__ \
  -Dit.test='SkitInviteCodeRegistryMySqlIT' \
  test-compile failsafe:integration-test failsafe:verify

JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/var/homebrew/tmp/.cellar/maven/3.9.16/libexec/bin/mvn \
  -pl yudao-module-skit -DskipTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=__NoSurefireTests__ \
  -Dit.test='*MySqlIT' \
  test-compile failsafe:integration-test failsafe:verify
```

## Static, security, and review gates

- `git diff --check`: clean.
- No production service/controller path calls the old agent-first/member-first invitation lookup methods.
- Registry mapper exposes only claim, active lookup, exact lock, and exact transition operations; it does not inherit unrestricted generic mutation APIs.
- Registry mutators have no independent Spring or dynamic-datasource transaction annotation and require an active outer boundary.
- No sleeps are used in concurrency integration tests; every latch/future is bounded to 30 seconds.
- Added-line Java 9+ API scan is clean for the Java 8 target.
- Added-line print/log scan is clean; the credential/token keyword scan contains only method names and the test password fixture `secret123`, not a deployable secret.
- Independent review found no remaining Critical or Important issue after the tenant-context and real dynamic-datasource rollback fixes. Its only Minor finding was this stale report, now replaced with the current evidence.

## Notes

- The test dynamic routing DataSource is intentionally minimal: it delegates to the Testcontainers MySQL DataSource while using the same dynamic-datasource `AbstractRoutingDataSource`, infrastructure advisor, transaction context, and connection proxy used by production.
- Task 3 does not change the Task 2 schema or deploy anything. It only centralizes ownership and transaction behavior needed by later verified-ad settlement tasks.
